package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.yayatechandinnovations.yayaagentic.channel.InboundMediaClient;
import com.yayatechandinnovations.yayaagentic.channel.MediaTranscriber;
import com.yayatechandinnovations.yayaagentic.testsupport.TestMedia;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transcription request (design §4.2 V3): language, domain prompt, model
 * and the response format each model accepts; the container length refuses a
 * long note before the API is called.
 */
class WhisperMediaTranscriberTest {

    private WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(post(urlEqualTo("/v1/audio/transcriptions")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"text\":\"necesito el cuadre\",\"language\":\"es\",\"duration\":13.4}")));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private WhisperMediaTranscriber transcriber(String model) {
        return new WhisperMediaTranscriber(InboundMediaClient.direct(), new ObjectMapper(), "sk-test",
                "http://localhost:" + server.port(), model, WhisperMediaTranscriber.DEFAULT_PROMPT, "es");
    }

    private static MediaTranscriber.TranscriptionContext ctx(String language) {
        return new MediaTranscriber.TranscriptionContext("t1", "s1", "SM1", Duration.ofSeconds(120),
                16L * 1024 * 1024, "Basic dXNlcjpwYXNz", language);
    }

    @Test
    void whisperRequestCarriesLanguagePromptModelAndVerboseJson() {
        MediaTranscriber.TranscriptionResult result = transcriber("whisper-1")
                .transcribeBytes(TestMedia.wav(3), "audio/wav", ctx("es-DO"));

        assertThat(result.text()).isEqualTo("necesito el cuadre");
        assertThat(result.audioDuration()).isEqualTo(Duration.ofSeconds(14));
        assertThat(result.modelId()).isEqualTo("whisper-1");
        String body = onlyRequest();
        assertThat(part(body, "model")).isEqualTo("whisper-1");
        assertThat(part(body, "language")).isEqualTo("es");
        assertThat(part(body, "response_format")).isEqualTo("verbose_json");
        assertThat(part(body, "prompt")).startsWith("quiniela, palé, tripleta");
        assertThat(body).contains("filename=\"SM1.wav\"");
    }

    @Test
    void theNewerModelsOnlyTakeJsonAndTheContainerSuppliesTheDuration() {
        server.resetRequests();
        server.stubFor(post(urlEqualTo("/v1/audio/transcriptions")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"text\":\"anular el 4411\"}")));

        MediaTranscriber.TranscriptionResult result = transcriber("gpt-4o-mini-transcribe")
                .transcribeBytes(TestMedia.oggOpus(9), "audio/ogg; codecs=opus", ctx(null));

        assertThat(result.text()).isEqualTo("anular el 4411");
        assertThat(result.audioDuration()).isEqualTo(Duration.ofSeconds(9));
        assertThat(result.detectedLanguage()).isEqualTo("es");
        String body = onlyRequest();
        assertThat(part(body, "response_format")).isEqualTo("json");
        assertThat(part(body, "model")).isEqualTo("gpt-4o-mini-transcribe");
        assertThat(body).contains("filename=\"SM1.ogg\"");
    }

    @Test
    void aNoteOverTheCapIsRefusedBeforeTheApiIsCalled() {
        assertThatThrownBy(() -> transcriber("whisper-1").transcribeBytes(TestMedia.wav(130), "audio/wav", ctx("es")))
                .isInstanceOf(WhisperMediaTranscriber.TranscriptionRejectedException.class)
                .hasFieldOrPropertyWithValue("code", "audio_too_long");
        assertThat(server.findAll(postRequestedFor(urlEqualTo("/v1/audio/transcriptions")))).isEmpty();
    }

    @Test
    void urlPathFetchesWithTheSendersBasicAuthThenTranscribes() {
        server.stubFor(get(urlEqualTo("/media/SM1"))
                .withHeader("Authorization", equalTo("Basic dXNlcjpwYXNz"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "audio/ogg")
                        .withBody(TestMedia.oggOpus(5))));

        MediaTranscriber.TranscriptionResult result = transcriber("whisper-1").transcribe(
                new MediaTranscriber.InboundMediaRef(URI.create("http://localhost:" + server.port() + "/media/SM1"),
                        "audio/ogg; codecs=opus", 0), ctx("es"));

        assertThat(result.text()).isEqualTo("necesito el cuadre");
    }

    @Test
    void languageIsTheTwoLetterCodeOrTheDefault() {
        WhisperMediaTranscriber t = transcriber("whisper-1");
        assertThat(t.languageFor(ctx("es-DO"))).isEqualTo("es");
        assertThat(t.languageFor(ctx("EN_us"))).isEqualTo("en");
        assertThat(t.languageFor(ctx(null))).isEqualTo("es");
        assertThat(t.responseFormat()).isEqualTo("verbose_json");
        assertThat(transcriber("gpt-4o-transcribe").responseFormat()).isEqualTo("json");
    }

    /** The value of one text part of a multipart body. */
    private static String part(String body, String name) {
        int at = body.indexOf("name=\"" + name + "\"");
        assertThat(at).as("part " + name).isPositive();
        int blank = body.indexOf("\r\n\r\n", at);
        int end = body.indexOf("\r\n--", blank);
        return body.substring(blank + 4, end);
    }

    private String onlyRequest() {
        List<LoggedRequest> requests = server.findAll(postRequestedFor(urlEqualTo("/v1/audio/transcriptions")));
        assertThat(requests).hasSize(1);
        return new String(requests.get(0).getBody(), StandardCharsets.UTF_8);
    }
}
