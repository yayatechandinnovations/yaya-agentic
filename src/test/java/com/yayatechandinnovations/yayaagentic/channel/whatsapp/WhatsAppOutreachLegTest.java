package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.channel.OutboundResult;
import com.yayatechandinnovations.yayaagentic.channel.ProviderCreds;
import com.yayatechandinnovations.yayaagentic.channel.ProviderCredsResolver;
import com.yayatechandinnovations.yayaagentic.channel.system.DeliveryRequest;
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
        leg = new WhatsAppOutreachLeg(senders, optOuts, templates, twilio, creds, inSession, inbound,
                mock(OutboundMessageRepository.class), signer, mock(ChannelMetrics.class), "https://hub.example/");

        sender = mock(WhatsAppSender.class);
        when(sender.status()).thenReturn(SenderStatus.ACTIVE);
        when(sender.phoneE164()).thenReturn("+18455336210");
        when(sender.id()).thenReturn(new SenderId(WA));
        when(senders.requireForTenant(TENANT, new SenderId(WA))).thenReturn(sender);
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
