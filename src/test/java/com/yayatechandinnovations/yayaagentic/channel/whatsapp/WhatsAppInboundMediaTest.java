package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.ChannelBindingDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppSenderDtos;
import com.yayatechandinnovations.yayaagentic.channel.MediaTranscriber;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingStatus;
import com.yayatechandinnovations.yayaagentic.channel.binding.RoutingRule;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.WhisperMediaTranscriber;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.operator_auth.TestAuthDance;
import com.yayatechandinnovations.yayaagentic.operator_auth.ratelimit.LoginRateLimiter;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAttachmentRepository;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderRepository;
import com.yayatechandinnovations.yayaagentic.recorder.ConversationRecorder;
import com.yayatechandinnovations.yayaagentic.recorder.TurnRange;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.testsupport.TestMedia;
import com.yayatechandinnovations.yayaagentic.usage.UsageMeter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Inbound WhatsApp media (assistant-channels design §3.3–§3.4, §4.2): a photo
 * or a PDF with no text is still a turn, PDFs are kept as attachments of the
 * session, codes in photos reach the model inside the data markers, and what
 * cannot be taken gets a fixed reply instead of silence.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Import({TestcontainersConfiguration.class, WhatsAppInboundMediaTest.Config.class})
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "yaya.agentic.channels.whatsapp.poller.enabled=false",
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WhatsAppInboundMediaTest {

    private static final String TENANT = "wa-media";
    private static final String BUSINESS_PHONE = "+15558000088";
    private static final AtomicReference<String> SENDER_ID = new AtomicReference<>();
    /** What the test transcriber does next: a transcript, or a rejection code. */
    private static final AtomicReference<String> TRANSCRIBER_MODE = new AtomicReference<>("ok");

    @Autowired WebTestClient client;
    @Autowired LoginRateLimiter rateLimiter;
    @Autowired InboundDispatchWorker worker;
    @Autowired InboundMessageRepository inbound;
    @Autowired ChannelSessionService channelSessions;
    @Autowired WhatsAppSenderRepository senderRows;
    @Autowired ChatAttachmentRepository attachments;
    @Autowired UsageMeter usage;
    @Autowired ConversationRecorder recorder;
    @MockitoSpyBean OutboundDispatcher outbound;

    private WireMockServer cdn;
    private String phone;

    @AfterAll
    void stopCdn() {
        cdn.stop();
    }

    @BeforeEach
    void resetPerTest() {
        rateLimiter.resetAll();
        TRANSCRIBER_MODE.set("ok");
        phone = "+1555" + String.format("%07d", (int) (Math.random() * 9_999_999));
    }

    /** One bootstrap of the admin client per class: the CSRF dance runs once on a fresh client. */
    @BeforeAll
    void setUp() {
        cdn = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        cdn.start();
        rateLimiter.resetAll();
        client = TestAuthDance.asBootstrap(client);
        client.post().uri("/v1/admin/tenants").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AdminDtos.TenantRequest(TENANT, "WA media", "https://wa-media.example",
                        List.of(), List.of(), true, null, null, Map.of()))
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
        client.post().uri("/v1/admin/profiles").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AdminDtos.ProfileRequest(TENANT, "wa-media-profile", "wa-media-profile",
                        "intro", "prompt fragment", List.of(), null, null, Map.of()))
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
        if (SENDER_ID.get() == null) {
            var created = client.post().uri("/v1/admin/tenants/{t}/whatsapp-senders", TENANT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new WhatsAppSenderDtos.SenderRequest(BUSINESS_PHONE, "WA media Co",
                            new WhatsAppSenderDtos.BusinessProfileRequest("WA media Inc.", "WA media Co",
                                    "ops@wa-media.example", "Address", "https://wa-media.example", "RETAIL",
                                    "Stuff."), null))
                    .exchange().expectStatus().isCreated()
                    .expectBody(WhatsAppSenderDtos.SenderResponse.class).returnResult().getResponseBody();
            SENDER_ID.set(created.id());
            senderRows.findById(created.id()).ifPresent(e -> {
                e.setStatus("ACTIVE");
                e.setTwilioSenderSid("SR_MEDIA_FAKE");
                senderRows.save(e);
            });
        }
        client.post().uri("/v1/admin/tenants/{t}/channel-bindings", TENANT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new ChannelBindingDtos.BindingRequest(
                        new ChannelBindingDtos.EndpointRequest("WHATSAPP_SENDER", SENDER_ID.get()),
                        "wa-media-profile", 1, new RoutingRule.MatchAny(), SessionPolicy.defaults(), 100,
                        BindingStatus.ACTIVE, null, null, null, null))
                .exchange().expectStatus().value(s -> assertThat(s).isIn(201, 409));
    }

    @Test
    void a_photo_with_no_text_is_a_turn_and_its_code_reaches_the_model_as_data() {
        cdn.stubFor(get(urlEqualTo("/m/ticket")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "image/png").withBody(TestMedia.qrPng("4130552218-7781902345", 300))));
        long imagesBefore = usage.usage(TENANT, null).imagesIn();

        InboundMessageEntity row = receive("", "image/png", "/m/ticket");

        assertThat(row.getProcessingError()).isNull();
        String userTurn = lastUserTurn().content();
        assertThat(userTurn).startsWith("(foto)")
                .contains("Código leído en la imagen: 4130552218-7781902345")
                .contains("<<<attachment-content");
        assertThat(usage.usage(TENANT, null).imagesIn()).isEqualTo(imagesBefore + 1);
        verify(outbound, never()).dispatchGreeting(any(), any(), eq(phone), any(), eq(InboundMediaGate.UNSUPPORTED_REPLY), any());
    }

    @Test
    void a_pdf_is_kept_as_an_attachment_of_the_session_and_read_as_data() {
        cdn.stubFor(get(urlEqualTo("/m/factura")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/pdf")
                .withHeader("Content-Disposition", "inline; filename=\"factura-4411.pdf\"")
                .withBody(TestMedia.pdf("Factura 4411 codigo 481516 total 1200"))));

        InboundMessageEntity row = receive("", "application/pdf", "/m/factura");

        assertThat(row.getProcessingError()).isNull();
        Ids.SessionId session = sessionOf();
        Turn user = lastUserTurn();
        assertThat(user.content()).contains("(documento: factura-4411.pdf)")
                .contains("attached document \"factura-4411.pdf\"")
                .contains("Factura 4411 codigo ••••••")
                .doesNotContain("481516");
        var rows = attachments.findBySessionId(session.value());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getKind()).isEqualTo("DOCUMENT");
        assertThat(rows.get(0).getFilename()).isEqualTo("factura-4411.pdf");
        assertThat(rows.get(0).getExtractedText()).contains("Factura 4411");
        assertThat(rows.get(0).getTurnId()).isNotNull();
        assertThat(user.metadata().get("attachments")).isNotNull();
    }

    @Test
    void video_gets_the_fixed_reply_and_no_turn() {
        InboundMessageEntity row = receive("", "video/mp4", "/m/clip");

        assertThat(row.getProcessingError()).isEqualTo("media_unsupported");
        verify(outbound).dispatchGreeting(any(), any(), eq(phone), any(), eq(InboundMediaGate.UNSUPPORTED_REPLY), any());
        assertThat(channelSessions.findOpen(new Ids.TenantId(TENANT), new SenderId(SENDER_ID.get()), phone)).isEmpty();
    }

    @Test
    void an_iphone_original_gets_the_jpg_or_png_hint_but_its_text_still_runs() {
        InboundMessageEntity row = receive("mira este ticket", "image/heic", "/m/heic");

        assertThat(row.getProcessingError()).isNull();
        verify(outbound).dispatchGreeting(any(), any(), eq(phone), any(), eq(InboundMediaGate.HEIC_REPLY), any());
        assertThat(lastUserTurn().content()).isEqualTo("mira este ticket");
    }

    @Test
    void a_voice_note_is_transcribed_in_the_senders_language_and_metered() {
        cdn.stubFor(get(urlEqualTo("/m/voice")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "audio/ogg").withBody(TestMedia.oggOpus(14))));
        long before = usage.usage(TENANT, null).voiceSeconds();

        InboundMessageEntity row = receive("", "audio/ogg; codecs=opus", "/m/voice");

        assertThat(row.getProcessingError()).isNull();
        assertThat(lastUserTurn().content()).isEqualTo("necesito el cuadre de hoy");
        assertThat(usage.usage(TENANT, null).voiceSeconds()).isEqualTo(before + 14);
        assertThat(row.getMediaTranscriptsJson()).contains("necesito el cuadre de hoy");
    }

    @Test
    void a_voice_note_over_two_minutes_or_one_that_fails_gets_a_fixed_reply() {
        TRANSCRIBER_MODE.set("audio_too_long");
        InboundMessageEntity tooLong = receive("", "audio/ogg; codecs=opus", "/m/voice-long");
        assertThat(tooLong.getProcessingError()).isEqualTo("media_unsupported");
        verify(outbound).dispatchGreeting(any(), any(), eq(phone), any(), eq(InboundMediaGate.VOICE_TOO_LONG_REPLY), any());

        TRANSCRIBER_MODE.set("transport_error");
        InboundMessageEntity failed = receive("", "audio/ogg; codecs=opus", "/m/voice-bad");
        assertThat(failed.getProcessingError()).isEqualTo("media_unsupported");
        verify(outbound).dispatchGreeting(any(), any(), eq(phone), any(), eq(InboundMediaGate.VOICE_UNAVAILABLE_REPLY), any());
    }

    /* ------------------------------- helpers ------------------------------- */

    private InboundMessageEntity receive(String body, String contentType, String mediaPath) {
        String sid = "SM_media_" + UUID.randomUUID();
        String url = "http://localhost:" + cdn.port() + mediaPath;
        String payload = "{\"MessageSid\":\"" + sid + "\",\"From\":\"whatsapp:" + phone + "\",\"To\":\"whatsapp:"
                + BUSINESS_PHONE + "\",\"Body\":\"" + body + "\",\"NumMedia\":\"1\",\"MediaUrl0\":\"" + url
                + "\",\"MediaContentType0\":\"" + contentType + "\"}";
        inbound.save(new InboundMessageEntity(sid, TENANT, SENDER_ID.get(), phone, 1, payload));
        worker.processSync(sid);
        InboundMessageEntity row = inbound.findById(sid).orElseThrow();
        assertThat(row.getProcessedAt()).isNotNull();
        return row;
    }

    private Ids.SessionId sessionOf() {
        return channelSessions.findOpen(new Ids.TenantId(TENANT), new SenderId(SENDER_ID.get()), phone).orElseThrow();
    }

    private Turn lastUserTurn() {
        List<Turn> turns = recorder.loadTurns(sessionOf(), TurnRange.all());
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i).role() == Turn.Role.USER) return turns.get(i);
        }
        throw new AssertionError("no user turn recorded");
    }

    @TestConfiguration
    static class Config {
        /** Stands in for Whisper: the worker's contract is the SPI, not the HTTP call. */
        @Bean
        @org.springframework.context.annotation.Primary
        MediaTranscriber testTranscriber() {
            return new MediaTranscriber() {
                @Override
                public Set<String> supportedContentTypes() {
                    return Set.of("audio/ogg", "audio/ogg; codecs=opus");
                }

                @Override
                public TranscriptionResult transcribe(InboundMediaRef media, TranscriptionContext ctx) {
                    String mode = TRANSCRIBER_MODE.get();
                    if (!"ok".equals(mode)) {
                        throw new WhisperMediaTranscriber.TranscriptionRejectedException(mode, "test");
                    }
                    assertThat(ctx.maxAudioDuration()).isEqualTo(Duration.ofSeconds(120));
                    return new TranscriptionResult("necesito el cuadre de hoy", "es", 1.0,
                            Duration.ofSeconds(14), "test");
                }
            };
        }
    }
}
