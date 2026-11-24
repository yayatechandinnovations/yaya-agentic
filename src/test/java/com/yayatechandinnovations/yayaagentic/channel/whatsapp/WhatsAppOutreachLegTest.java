package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.channel.OutboundMedia;
import com.yayatechandinnovations.yayaagentic.channel.OutboundResult;
import com.yayatechandinnovations.yayaagentic.channel.ProviderCreds;
import com.yayatechandinnovations.yayaagentic.channel.ProviderCredsResolver;
import com.yayatechandinnovations.yayaagentic.channel.ProviderId;
import com.yayatechandinnovations.yayaagentic.channel.system.DeliveryRequest;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundOutboundSink;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundSessionRegistry;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.TemplateCategory;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.TemplateId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.TemplateStatus;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.WhatsAppTemplate;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.WhatsAppTemplateService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioMessagingChannelProvider;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.outreach.ActionItem;
import com.yayatechandinnovations.yayaagentic.outreach.media.MediaTokenSigner;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachDeliveryRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderAssignmentEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** GAP-P1 of whatsapp-ops-integration-design.md §6.2: template vs in-session,
 *  variable layout, and every fallback reason. */
class WhatsAppOutreachLegTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("acme");
    private static final String WA = "wa_1";
    private static final String PHONE = "+18095551234";

    private WhatsAppSenderService senders;
    private OptOutService optOuts;
    private WhatsAppTemplateService templates;
    private TwilioMessagingChannelProvider twilio;
    private ProviderCredsResolver creds;
    private InSessionContents inSession;
    private InboundMessageRepository inbound;
    private MediaTokenSigner signer;
    private WhatsAppOutreachLeg leg;
    private WhatsAppSender sender;
    private PlaygroundOutboundSink sink;
    private PlaygroundSessionRegistry playgroundSessions;

    @BeforeEach
    void setUp() {
        senders = mock(WhatsAppSenderService.class);
        optOuts = mock(OptOutService.class);
        templates = mock(WhatsAppTemplateService.class);
        twilio = mock(TwilioMessagingChannelProvider.class);
        creds = mock(ProviderCredsResolver.class);
        inSession = mock(InSessionContents.class);
        inbound = mock(InboundMessageRepository.class);
        signer = mock(MediaTokenSigner.class);
        sink = new PlaygroundOutboundSink();
        playgroundSessions = new PlaygroundSessionRegistry();
        leg = new WhatsAppOutreachLeg(senders, optOuts, templates, twilio, creds, inSession, inbound,
                mock(OutboundMessageRepository.class), signer, mock(ChannelMetrics.class), sink,
                playgroundSessions, "https://hub.example/");

        sender = mock(WhatsAppSender.class);
        when(sender.status()).thenReturn(SenderStatus.ACTIVE);
        when(sender.phoneE164()).thenReturn("+18455336210");
        when(sender.id()).thenReturn(new SenderId(WA));
        when(sender.onboarding()).thenReturn(OnboardingState.empty());
        when(senders.requireUsable(TENANT, new SenderId(WA))).thenReturn(sender);
        when(senders.isAssignmentActive(any(), any())).thenReturn(true);
        when(senders.labelled(any(), any(), anyString())).thenAnswer(inv -> inv.getArgument(2));
        when(creds.isReady()).thenReturn(true);
        when(creds.resolve(sender)).thenReturn(mock(ProviderCreds.class));
        when(signer.mint(eq(TENANT), anyString())).thenReturn("TOKEN.sig");
        when(twilio.sendContent(anyString(), anyString(), anyMap(), anyMap(), any()))
                .thenReturn(new OutboundResult.Sent("SM1", Instant.now()));
    }

    @Test
    void outside_the_window_sends_the_approved_template_with_document_header_and_action_payloads() {
        when(inbound.findLatestForSenderAndEndUserSince(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(templates.findApproved(TENANT, sender, "sl_undo_request_v1", "es"))
                .thenReturn(Optional.of(template("DOCUMENT", 6, List.of("Aprobar", "Rechazar", "Ver detalle"))));

        var outcome = leg.deliver(request(List.of(new WhatsAppOutreachLeg.Media("asset1", "ticket 12.pdf",
                "application/pdf"))));

        assertThat(outcome.delivered()).isTrue();
        assertThat(outcome.templateName()).isEqualTo("sl_undo_request_v1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> vars = ArgumentCaptor.forClass(Map.class);
        verify(twilio).sendContent(eq(PHONE), eq("HXtemplate"), vars.capture(), anyMap(), any());
        assertThat(vars.getValue())
                .containsEntry("1", "UR-1")
                .containsEntry("6", "El cliente se equivocó de número")
                .containsEntry("7", "act:a1")
                .containsEntry("8", "act:a2")
                .containsEntry("9", "act:a3")
                .containsEntry("10", "TOKEN.sig/ticket_12.pdf");
        assertThat(outcome.deliveredBody()).contains("UR-1").doesNotContain("{{");
    }

    @Test
    void inside_the_window_sends_an_in_session_card_with_native_buttons() {
        when(inbound.findLatestForSenderAndEndUserSince(anyString(), anyString(), any()))
                .thenReturn(Optional.of(mock(InboundMessageEntity.class)));
        when(inSession.mediaCard(eq(3), any())).thenReturn(Optional.of("HXcard3"));

        var outcome = leg.deliver(request(List.of(new WhatsAppOutreachLeg.Media("asset1", "t.pdf",
                "application/pdf"))));

        assertThat(outcome.delivered()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> vars = ArgumentCaptor.forClass(Map.class);
        verify(twilio).sendContent(eq(PHONE), eq("HXcard3"), vars.capture(), anyMap(), any());
        assertThat(vars.getValue()).containsEntry("2", "TOKEN.sig/t.pdf")
                .containsEntry("3", "Aprobar").containsEntry("4", "act:a1")
                .containsEntry("7", "Ver detalle").containsEntry("8", "act:a3");
        verify(templates, never()).findApproved(any(), any(), anyString(), anyString());
    }

    @Test
    void inside_the_window_a_report_without_buttons_is_one_document_with_the_text_as_caption() {
        when(inbound.findLatestForSenderAndEndUserSince(anyString(), anyString(), any()))
                .thenReturn(Optional.of(mock(InboundMessageEntity.class)));
        when(twilio.id()).thenReturn(ProviderId.TWILIO);
        when(twilio.sendMedia(any(), any(), any())).thenReturn(new OutboundResult.Sent("MM1", Instant.now()));
        var report = new WhatsAppOutreachLeg.Request(TENANT, WA, PHONE, "Cuadre por fecha — 17 nov 2026.",
                List.of(new WhatsAppOutreachLeg.Media("asset1", "cuadre-2026-11-17.pdf", "application/pdf")),
                List.of(), null);

        var outcome = leg.deliver(report);

        assertThat(outcome.delivered()).isTrue();
        assertThat(outcome.providerMessageSid()).isEqualTo("MM1");
        ArgumentCaptor<OutboundMedia> media = ArgumentCaptor.forClass(OutboundMedia.class);
        verify(twilio).sendMedia(any(), media.capture(), any());
        assertThat(media.getValue().caption()).isEqualTo("Cuadre por fecha — 17 nov 2026.");
        assertThat(media.getValue().mediaUrl()).isEqualTo("https://hub.example/v1/media/TOKEN.sig/cuadre-2026-11-17.pdf");
        verify(twilio, never()).sendContent(anyString(), anyString(), anyMap(), anyMap(), any());
        verify(twilio, never()).sendFreeform(any(), any(), any());
    }

    @Test
    void fallback_reasons() {
        when(inbound.findLatestForSenderAndEndUserSince(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(templates.findApproved(any(), any(), anyString(), anyString())).thenReturn(Optional.empty());
        assertThat(leg.deliver(request(List.of())).fallbackReason()).isEqualTo("template_not_approved");

        var noSender = new WhatsAppOutreachLeg.Request(TENANT, null, PHONE, "b", List.of(), List.of(), null);
        assertThat(leg.deliver(noSender).fallbackReason()).isEqualTo("no_whatsapp_sender");

        var noTemplate = new WhatsAppOutreachLeg.Request(TENANT, WA, PHONE, "b", List.of(), List.of(), null);
        assertThat(leg.deliver(noTemplate).fallbackReason()).isEqualTo("no_template");

        when(optOuts.isOptedOut(new SenderId(WA), PHONE)).thenReturn(true);
        assertThat(leg.deliver(noTemplate).fallbackReason()).isEqualTo("opted_out");

        when(optOuts.isOptedOut(new SenderId(WA), PHONE)).thenReturn(false);
        when(sender.status()).thenReturn(SenderStatus.SUSPENDED);
        assertThat(leg.deliver(noTemplate).fallbackReason()).isEqualTo("sender_inactive");
    }

    @Test
    void a_document_template_without_media_is_not_sent_half_empty() {
        when(inbound.findLatestForSenderAndEndUserSince(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(templates.findApproved(any(), any(), anyString(), anyString()))
                .thenReturn(Optional.of(template("DOCUMENT", 6, List.of("Aprobar"))));

        assertThat(leg.deliver(request(List.of())).fallbackReason()).isEqualTo("no_template");
        verify(twilio, never()).sendContent(anyString(), anyString(), anyMap(), anyMap(), any());
    }

    @Test
    void the_tenants_playground_number_lands_in_the_conversation_of_that_phone_with_its_buttons() {
        playgroundSessions.rememberPhone(TENANT, PHONE, "wa-1700000000000");
        var r = new WhatsAppOutreachLeg.Request(TENANT, "snd_playground_sim__acme", PHONE,
                "Solicitud UR-1 pendiente", List.of(), request(List.of()).actions(),
                new DeliveryRequest.TemplateRef("sl_undo_request_v1", "es", List.of("UR-1")));

        var outcome = leg.deliver(r);

        assertThat(outcome.delivered()).isTrue();
        assertThat(outcome.fallbackReason()).isNull();
        assertThat(outcome.templateName()).isNull();
        assertThat(outcome.deliveredBody()).isEqualTo("Solicitud UR-1 pendiente");
        var messages = sink.drain(new Ids.SessionId("wa-1700000000000"), null);
        assertThat(messages).singleElement().satisfies(m -> {
            assertThat(outcome.providerMessageSid()).isEqualTo("playground:" + m.id());
            assertThat(m.body()).startsWith("Solicitud UR-1 pendiente").contains("1. Aprobar");
            assertThat(m.interactive().kind()).isEqualTo("buttons");
            assertThat(m.interactive().options()).extracting(o -> o.get("id"))
                    .containsExactly("act:a1", "act:a2", "act:a3");
            assertThat(m.interactive().options()).extracting(o -> o.get("label"))
                    .containsExactly("Aprobar", "Rechazar", "Ver detalle");
        });
        verify(twilio, never()).sendContent(anyString(), anyString(), anyMap(), anyMap(), any());
        verify(templates, never()).findApproved(any(), any(), anyString(), anyString());
    }

    @Test
    void the_playground_number_without_a_conversation_for_that_phone_fails_and_another_tenants_is_no_sender() {
        var none = new WhatsAppOutreachLeg.Request(TENANT, "snd_playground_sim__acme", PHONE, "b",
                List.of(), List.of(), null);
        assertThat(leg.deliver(none).fallbackReason()).isEqualTo("no_playground_session");

        playgroundSessions.rememberPhone(new Ids.TenantId("other"), PHONE, "wa-other");
        assertThat(leg.deliver(none).fallbackReason()).isEqualTo("no_playground_session");

        playgroundSessions.rememberPhone(TENANT, PHONE, "wa-1");
        var foreign = new WhatsAppOutreachLeg.Request(TENANT, "snd_playground_sim__other", PHONE, "b",
                List.of(), List.of(), null);
        assertThat(leg.deliver(foreign).fallbackReason()).isEqualTo("no_whatsapp_sender");
        assertThat(sink.size(new Ids.SessionId("wa-other"))).isZero();
        assertThat(sink.size(new Ids.SessionId("wa-1"))).isZero();
    }

    /* ---------------- platform (shared) senders — shared-sender design §9 ---------------- */

    @Test
    void on_a_platform_sender_the_platform_fills_the_business_name_and_the_caller_keeps_its_list() {
        when(sender.isPlatform()).thenReturn(true);
        when(senders.assignmentLabel(TENANT, sender)).thenReturn(Optional.of("Banca La Suerte"));
        when(senders.assignment(TENANT, sender)).thenReturn(Optional.of(assignment(1000)));
        OutreachDeliveryRepository deliveries = mock(OutreachDeliveryRepository.class);
        leg.setSharedSenderSupport(deliveries, mock(TenantRepository.class));
        when(inbound.findLatestForSenderAndEndUserSince(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(templates.findApproved(TENANT, sender, "sl_undo_request_v1", "es"))
                .thenReturn(Optional.of(labelled(template("NONE", 7, List.of()), 1)));

        var outcome = leg.deliver(request(List.of()));

        assertThat(outcome.delivered()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> vars = ArgumentCaptor.forClass(Map.class);
        verify(twilio).sendContent(eq(PHONE), eq("HXtemplate"), vars.capture(), anyMap(), any());
        assertThat(vars.getValue())
                .containsEntry("1", "Banca La Suerte")
                .containsEntry("2", "UR-1")
                .containsEntry("7", "El cliente se equivocó de número");
        assertThat(outcome.deliveredBody()).contains("Banca La Suerte").contains("UR-1");
    }

    @Test
    void a_caller_cannot_put_another_business_name_where_the_label_goes() {
        when(sender.isPlatform()).thenReturn(false);
        TenantRepository tenants = mock(TenantRepository.class);
        when(tenants.findById("acme")).thenReturn(Optional.of(new TenantEntity("acme", "Acme Bancas")));
        leg.setSharedSenderSupport(mock(OutreachDeliveryRepository.class), tenants);
        when(senders.assignmentLabel(TENANT, sender)).thenReturn(Optional.empty());
        when(inbound.findLatestForSenderAndEndUserSince(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(templates.findApproved(TENANT, sender, "sl_undo_request_v1", "es"))
                .thenReturn(Optional.of(labelled(template("NONE", 3, List.of()), 2)));

        leg.deliver(request(List.of()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> vars = ArgumentCaptor.forClass(Map.class);
        verify(twilio).sendContent(eq(PHONE), eq("HXtemplate"), vars.capture(), anyMap(), any());
        // On the workspace's own number the name is the workspace's display name.
        assertThat(vars.getValue()).containsEntry("1", "UR-1").containsEntry("2", "Acme Bancas")
                .containsEntry("3", "B-04");
    }

    @Test
    void on_a_platform_sender_a_template_that_does_not_name_the_business_falls_back() {
        when(sender.isPlatform()).thenReturn(true);
        when(inbound.findLatestForSenderAndEndUserSince(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(templates.findApproved(TENANT, sender, "sl_undo_request_v1", "es"))
                .thenReturn(Optional.of(template("NONE", 6, List.of())));

        var outcome = leg.deliver(request(List.of()));

        assertThat(outcome.delivered()).isFalse();
        assertThat(outcome.fallbackReason()).isEqualTo("template_not_shared_safe");
        verify(twilio, never()).sendContent(anyString(), anyString(), anyMap(), anyMap(), any());
    }

    @Test
    void a_workspace_over_its_daily_share_of_template_sends_falls_back() {
        when(sender.isPlatform()).thenReturn(true);
        when(senders.assignment(TENANT, sender)).thenReturn(Optional.of(assignment(5)));
        OutreachDeliveryRepository deliveries = mock(OutreachDeliveryRepository.class);
        when(deliveries.countByTenantIdAndWhatsappSenderIdAndTemplateNameIsNotNullAndFallbackReasonIsNullAndCreatedAtAfter(
                eq("acme"), eq(WA), any())).thenReturn(5L);
        leg.setSharedSenderSupport(deliveries, mock(TenantRepository.class));
        when(inbound.findLatestForSenderAndEndUserSince(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(templates.findApproved(TENANT, sender, "sl_undo_request_v1", "es"))
                .thenReturn(Optional.of(labelled(template("NONE", 7, List.of()), 1)));

        var outcome = leg.deliver(request(List.of()));

        assertThat(outcome.fallbackReason()).isEqualTo("assignment_cap_reached");
        verify(twilio, never()).sendContent(anyString(), anyString(), anyMap(), anyMap(), any());
    }

    @Test
    void a_paused_assignment_sends_nothing() {
        when(senders.isAssignmentActive(TENANT, sender)).thenReturn(false);

        var outcome = leg.deliver(request(List.of()));

        assertThat(outcome.fallbackReason()).isEqualTo("sender_inactive");
    }

    @Test
    void in_session_text_goes_out_as_the_sender_service_labels_it() {
        when(inbound.findLatestForSenderAndEndUserSince(anyString(), anyString(), any()))
                .thenReturn(Optional.of(mock(InboundMessageEntity.class)));
        when(senders.labelled(TENANT, sender, "Solicitud UR-1 pendiente"))
                .thenReturn("*Banca La Suerte*\nSolicitud UR-1 pendiente");
        when(inSession.quickReply(eq(3), any())).thenReturn(Optional.of("HXqr3"));

        var outcome = leg.deliver(request(List.of()));

        assertThat(outcome.deliveredBody()).startsWith("*Banca La Suerte*");
    }

    private static WhatsAppTemplate labelled(WhatsAppTemplate t, int labelVar) {
        return new WhatsAppTemplate(t.id(), t.accountKey(), t.name(), t.locale(), t.category(), t.body(),
                t.placeholderCount(), t.bspTemplateId(), t.bspContentSid(), t.status(), t.rejectionReason(),
                t.submittedAt(), t.approvedAt(), t.lastSyncAt(), t.headerKind(), t.buttons(), t.hiddenAt(),
                t.hiddenBy(), t.shared(), labelVar);
    }

    private static WhatsAppSenderAssignmentEntity assignment(int cap) {
        return new WhatsAppSenderAssignmentEntity("wsa_1", WA, "acme", "Banca La Suerte", cap, "test");
    }

    @Test
    void template_parameters_follow_meta_rules() {
        assertThat(WhatsAppOutreachLeg.sanitizeParam("a\nb\tc")).isEqualTo("a b c");
        assertThat(WhatsAppOutreachLeg.sanitizeParam("a      b")).isEqualTo("a   b");
        assertThat(WhatsAppOutreachLeg.sanitizeParam("  ")).isEqualTo("—");
        assertThat(WhatsAppOutreachLeg.sanitizeParam("x".repeat(200))).hasSize(120);
    }

    private WhatsAppOutreachLeg.Request request(List<WhatsAppOutreachLeg.Media> media) {
        List<ActionItem> actions = List.of(
                new ActionItem("Aprobar", null, "primary",
                        new ActionItem.BoundTool("undo_request_approve", Map.of("requestNumber", "UR-1")), null, "a1"),
                new ActionItem("Rechazar", null, "danger",
                        new ActionItem.BoundTool("undo_request_reject", Map.of("requestNumber", "UR-1")), null, "a2"),
                new ActionItem("Ver detalle", "Ver el detalle de UR-1", "neutral", null, null, "a3"));
        return new WhatsAppOutreachLeg.Request(TENANT, WA, PHONE, "Solicitud UR-1 pendiente", media, actions,
                new DeliveryRequest.TemplateRef("sl_undo_request_v1", "es",
                        List.of("UR-1", "B-04", "Ana", "120", "RD$350.00", "El cliente se\nequivocó de número")));
    }

    private static WhatsAppTemplate template(String header, int n, List<String> buttons) {
        StringBuilder body = new StringBuilder("Solicitud");
        for (int i = 1; i <= n; i++) body.append(" v").append(i).append(": {{").append(i).append("}}");
        body.append(".");
        return new WhatsAppTemplate(new TemplateId(1L), "platform", "sl_undo_request_v1", "es",
                TemplateCategory.UTILITY, body.toString(), n, null, "HXtemplate", TemplateStatus.APPROVED,
                null, null, null, null, header, buttons, null, null, false);
    }
}
