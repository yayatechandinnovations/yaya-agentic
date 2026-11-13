package com.yayatechandinnovations.yayaagentic.outreach;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.system.AddressingPolicy;
import com.yayatechandinnovations.yayaagentic.channel.system.Correlation;
import com.yayatechandinnovations.yayaagentic.channel.system.DeliveryRequest;
import com.yayatechandinnovations.yayaagentic.channel.system.MediationMode;
import com.yayatechandinnovations.yayaagentic.channel.system.OutreachRecipient;
import com.yayatechandinnovations.yayaagentic.channel.system.SenderTrust;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemInboundMessage;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSender;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSenderService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppOutreachLeg;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.IntentFrame;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.core.Session;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.StartSessionResult;
import com.yayatechandinnovations.yayaagentic.outreach.resolvers.ByPrincipalRecipientResolver;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ChatInboxEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatInboxRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachDeliveryEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachDeliveryRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachSessionRepository;
import com.yayatechandinnovations.yayaagentic.persistence.SystemInboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.recorder.ConversationRecorder;
import com.yayatechandinnovations.yayaagentic.recorder.RecorderRouter;
import com.yayatechandinnovations.yayaagentic.recorder.TurnRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * whatsapp-ops-integration-design.md §6.1/§6.2: a WHATSAPP preference goes out
 * on the sender's linked number when it can and falls back to the chat inbox
 * (with the reason on the receipt) when it cannot; every action is registered
 * and reaches the chat client by id.
 */
class OutreachWhatsAppRoutingTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("acme");
    private static final Ids.SystemSenderId SENDER = new Ids.SystemSenderId("sl-owner-ops");
    private static final String PHONE = "+18095551234";
    private static final String SUBJECT = "chat:" + PHONE;

    private ChatInboxRepository chatInbox;
    private WhatsAppOutreachLeg leg;
    private OutreachActionService actions;
    private OutreachDeliveryRepository deliveries;
    private DefaultOutreachService service;

    @BeforeEach
    void setUp() {
        SystemSenderService senders = mock(SystemSenderService.class);
        ConversationEngine engine = mock(ConversationEngine.class);
        RecorderRouter router = mock(RecorderRouter.class);
        ConversationRecorder recorder = mock(ConversationRecorder.class);
        ChatEntryPointRepository entryPoints = mock(ChatEntryPointRepository.class);
        OutreachSessionRepository outreachSessions = mock(OutreachSessionRepository.class);
        SystemInboundMessageRepository ledger = mock(SystemInboundMessageRepository.class);
        chatInbox = mock(ChatInboxRepository.class);
        leg = mock(WhatsAppOutreachLeg.class);
        actions = mock(OutreachActionService.class);
        deliveries = mock(OutreachDeliveryRepository.class);

        service = new DefaultOutreachService(senders, List.of(new ByPrincipalRecipientResolver()), engine, router,
                entryPoints, outreachSessions, chatInbox, ledger, mock(MediationFramer.class),
                mock(MediationService.class),
                mock(com.yayatechandinnovations.yayaagentic.outreach.media.MediaLibraryService.class),
                mock(com.yayatechandinnovations.yayaagentic.outreach.push.PushNotifier.class), new ObjectMapper());
        service.setActionService(actions);
        service.setWhatsappLeg(leg);
        service.setDeliveries(deliveries);

        when(senders.find(TENANT, SENDER)).thenReturn(Optional.of(new SystemSender(SENDER, TENANT, "staff_ep",
                "Operaciones", true, new SenderTrust("shared-secret-jwt", Set.of(), ""), MediationMode.VERBATIM,
                Optional.empty(), AddressingPolicy.principalsOnly(), Optional.empty(), Optional.empty(),
                Instant.now(), Instant.now(), Optional.of("wa_1"))));
        when(ledger.findByTenantIdAndSenderIdAndDedupKey(any(), any(), any())).thenReturn(Optional.empty());
        when(router.recorderFor(any(), any())).thenReturn(recorder);
        when(recorder.loadTurns(any(), any(TurnRange.class))).thenReturn(List.of());
        when(outreachSessions.findByTenantIdAndEntryPointIdAndRecipientKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(entryPoints.findByTenantIdAndId(TENANT.value(), "staff_ep")).thenReturn(Optional.of(
                new ChatEntryPointEntity("staff_ep", TENANT.value(), "staff", "owner_ops_v1", 1, "Staff",
                        null, "{}", "ACTIVE")));
        Session minted = new Session(new Ids.SessionId("sess-1"), TENANT,
                new Principal(SUBJECT, TENANT, Set.of(), Map.of(), Instant.now()),
                new Ids.ProfileId("owner_ops_v1", 1), "chat", List.of(), IntentFrame.empty(), Map.of(),
                Instant.now(), null);
        when(engine.start(any(), any())).thenReturn(new StartSessionResult(minted, minted.profile(), "", List.of(), null));
        when(actions.register(eq(TENANT), any(), anyList(), eq(SUBJECT), eq(PHONE))).thenAnswer(inv -> {
            List<ActionItem> in = inv.getArgument(2);
            return List.of(in.get(0).withActionId("a1"), in.get(1).withActionId("a2"));
        });
    }

    @Test
    void whatsapp_delivery_skips_the_chat_inbox_and_reports_whatsapp() {
        when(leg.deliver(any())).thenReturn(new WhatsAppOutreachLeg.Outcome(true, null, "SM9",
                "sl_undo_request_v1", "Solicitud UR-1 pendiente", null));

        var receipt = service.ingest(message(), new OutreachService.OutreachAuth(posPrincipal()));

        ArgumentCaptor<WhatsAppOutreachLeg.Request> req = ArgumentCaptor.forClass(WhatsAppOutreachLeg.Request.class);
        verify(leg).deliver(req.capture());
        assertThat(req.getValue().whatsappSenderId()).isEqualTo("wa_1");
        assertThat(req.getValue().actions()).extracting(ActionItem::actionId).containsExactly("a1", "a2");
        verify(chatInbox, never()).save(any());
        assertThat(receipt.deliveries()).singleElement().satisfies(d -> {
            assertThat(d.channel()).isEqualTo("WHATSAPP");
            assertThat(d.providerMessageId()).isEqualTo("SM9");
        });
        ArgumentCaptor<OutreachDeliveryEntity> row = ArgumentCaptor.forClass(OutreachDeliveryEntity.class);
        verify(deliveries).save(row.capture());
        assertThat(row.getValue().getEndUserPhone()).isEqualTo(PHONE);
        assertThat(row.getValue().getBody()).isEqualTo("Solicitud UR-1 pendiente");
    }

    @Test
    void fallback_lands_in_the_chat_with_action_ids_and_the_reason() {
        when(leg.deliver(any())).thenReturn(new WhatsAppOutreachLeg.Outcome(false, "template_not_approved",
                null, null, null, null));

        var receipt = service.ingest(message(), new OutreachService.OutreachAuth(posPrincipal()));

        ArgumentCaptor<ChatInboxEntity> inbox = ArgumentCaptor.forClass(ChatInboxEntity.class);
        verify(chatInbox).save(inbox.capture());
        assertThat(inbox.getValue().getActionsJson())
                .contains("\"actionId\":\"a1\"").contains("\"actionId\":\"a2\"")
                .doesNotContain("undo_request_approve");
        assertThat(receipt.deliveries()).singleElement().satisfies(d -> {
            assertThat(d.channel()).isEqualTo("CHAT");
            assertThat(d.fallbackReason()).isEqualTo("template_not_approved");
        });
    }

    private SystemInboundMessage message() {
        List<ActionItem> acts = List.of(
                new ActionItem("Aprobar", null, "primary",
                        new ActionItem.BoundTool("undo_request_approve", Map.of("requestNumber", "UR-1")), null, null),
                new ActionItem("Ver detalle", "Ver el detalle de UR-1", "neutral"));
        return new SystemInboundMessage(TENANT, SENDER, new OutreachRecipient.ByPrincipal(SUBJECT),
                "Solicitud UR-1 pendiente", List.of(), new Correlation("POS", "UR-1", "Solicitud"),
                "dedup-1", Map.of(), acts,
                new DeliveryRequest(DeliveryRequest.Channel.WHATSAPP, PHONE,
                        new DeliveryRequest.TemplateRef("sl_undo_request_v1", "es", List.of("UR-1"))));
    }

    private static Principal posPrincipal() {
        return new Principal("sl", TENANT, Set.of(SenderTrust.SEND_SCOPE), Map.of(), Instant.now());
    }
}
