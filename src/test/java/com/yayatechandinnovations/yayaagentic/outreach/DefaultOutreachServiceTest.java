package com.yayatechandinnovations.yayaagentic.outreach;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.system.AddressingPolicy;
import com.yayatechandinnovations.yayaagentic.channel.system.Correlation;
import com.yayatechandinnovations.yayaagentic.channel.system.MediationMode;
import com.yayatechandinnovations.yayaagentic.channel.system.OutreachRecipient;
import com.yayatechandinnovations.yayaagentic.channel.system.SenderTrust;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemInboundMessage;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSender;
import com.yayatechandinnovations.yayaagentic.channel.system.SystemSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.IntentFrame;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.core.Session;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.StartSessionResult;
import com.yayatechandinnovations.yayaagentic.outreach.resolvers.ByPrincipalRecipientResolver;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ChatInboxEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatInboxRepository;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachSessionEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachSessionRepository;
import com.yayatechandinnovations.yayaagentic.persistence.SystemInboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SystemInboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.recorder.ConversationRecorder;
import com.yayatechandinnovations.yayaagentic.recorder.RecorderContext;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SM-0 acceptance: a shared-secret-JWT-authed POS posts a closure message to an
 * operator; it lands in their chat (via {@code chat_inbox}), recorded as a
 * non-executable SYSTEM_PARTICIPANT Turn, idempotent on the dedup key.
 */
class DefaultOutreachServiceTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("acme");
    private static final Ids.SystemSenderId SENDER = new Ids.SystemSenderId("sys_pos3");
    private static final String OPERATOR = "op-42";
    private static final String ENTRY_POINT = "chat_cashdesk";

    private SystemSenderService senders;
    private ConversationEngine engine;
    private RecorderRouter recorderRouter;
    private ConversationRecorder recorder;
    private ChatEntryPointRepository entryPoints;
    private OutreachSessionRepository outreachSessions;
    private ChatInboxRepository chatInbox;
    private SystemInboundMessageRepository ledger;
    private DefaultOutreachService service;

    @BeforeEach
    void setUp() {
        senders = mock(SystemSenderService.class);
        engine = mock(ConversationEngine.class);
        recorderRouter = mock(RecorderRouter.class);
        recorder = mock(ConversationRecorder.class);
        entryPoints = mock(ChatEntryPointRepository.class);
        outreachSessions = mock(OutreachSessionRepository.class);
        chatInbox = mock(ChatInboxRepository.class);
        ledger = mock(SystemInboundMessageRepository.class);

        MediationFramer framer = mock(MediationFramer.class);
        MediationService mediation = mock(MediationService.class);
        var mediaLibrary = mock(
                com.yayatechandinnovations.yayaagentic.outreach.media.MediaLibraryService.class);
        var pushNotifier = mock(com.yayatechandinnovations.yayaagentic.outreach.push.PushNotifier.class);
        service = new DefaultOutreachService(senders, List.of(new ByPrincipalRecipientResolver()),
                engine, recorderRouter, entryPoints, outreachSessions, chatInbox, ledger,
                framer, mediation, mediaLibrary, pushNotifier, new ObjectMapper());

        when(senders.find(TENANT, SENDER)).thenReturn(Optional.of(enabledSender()));
        when(recorderRouter.recorderFor(any(), any())).thenReturn(recorder);
        when(recorder.loadTurns(any(), any(TurnRange.class))).thenReturn(List.of());
    }

    @Test
    void deliversVerbatimToInboxAsSystemParticipantTurn() {
        // Fresh dedup key, no existing outreach session → engine mints one.
        when(ledger.findByTenantIdAndSenderIdAndDedupKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(outreachSessions.findByTenantIdAndEntryPointIdAndRecipientKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(entryPoints.findByTenantIdAndId(TENANT.value(), ENTRY_POINT))
                .thenReturn(Optional.of(entryPointEntity()));
        Session minted = session("sess-1");
        when(engine.start(any(), any()))
                .thenReturn(new StartSessionResult(minted, minted.profile(), "hi", List.of(), null));

        var receipt = service.ingest(message("closure-2026-07-03"),
                new OutreachService.OutreachAuth(posPrincipal()));

        assertThat(receipt.disposition()).isEqualTo(OutreachService.Disposition.DELIVERED);
        assertThat(receipt.sessionIds()).containsExactly("sess-1");

        // Recorded as a SYSTEM turn tagged SYSTEM_PARTICIPANT, body verbatim, no tool calls.
        ArgumentCaptor<Turn> turn = ArgumentCaptor.forClass(Turn.class);
        verify(recorder).onTurnRecorded(eq(minted.id()), turn.capture(), any(RecorderContext.class));
        assertThat(turn.getValue().role()).isEqualTo(Turn.Role.SYSTEM);
        assertThat(turn.getValue().content()).isEqualTo("cash closed: 1000");
        assertThat(turn.getValue().toolCalls()).isEmpty();
        assertThat(turn.getValue().metadata())
                .containsEntry("origin", "SYSTEM_PARTICIPANT")
                .containsEntry("senderId", SENDER.value());

        // Enqueued for chat drain under the operator's principal key.
        ArgumentCaptor<ChatInboxEntity> inbox = ArgumentCaptor.forClass(ChatInboxEntity.class);
        verify(chatInbox).save(inbox.capture());
        assertThat(inbox.getValue().getRecipientKey()).isEqualTo("principal:" + OPERATOR);
        assertThat(inbox.getValue().getBody()).isEqualTo("cash closed: 1000");
        assertThat(inbox.getValue().getSessionId()).isEqualTo("sess-1");

        // Durable index persisted so a later message reuses the thread.
        verify(outreachSessions).save(any(OutreachSessionEntity.class));
    }

    @Test
    void writesInlineActionsToInboxActionsJson() {
        when(ledger.findByTenantIdAndSenderIdAndDedupKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(outreachSessions.findByTenantIdAndEntryPointIdAndRecipientKey(any(), any(), any()))
                .thenReturn(Optional.of(new OutreachSessionEntity(
                        TENANT.value(), ENTRY_POINT, "principal:" + OPERATOR, "sess-1", "cashier", 1)));

        SystemInboundMessage withActions = new SystemInboundMessage(TENANT, SENDER,
                new OutreachRecipient.ByPrincipal(OPERATOR), "cash short by 200",
                java.util.List.of(),
                new Correlation("Cash Register #3", "cb-9", "cash closure"),
                "closure-actions",
                Map.of(),
                java.util.List.of(new ActionItem("Confirmar", "/confirm cb-9", "primary"),
                        new ActionItem("Disputar", "/dispute cb-9", "danger")));

        service.ingest(withActions, new OutreachService.OutreachAuth(posPrincipal()));

        ArgumentCaptor<ChatInboxEntity> inbox = ArgumentCaptor.forClass(ChatInboxEntity.class);
        verify(chatInbox).save(inbox.capture());
        String actionsJson = inbox.getValue().getActionsJson();
        assertThat(actionsJson)
                .contains("\"label\":\"Confirmar\"")
                .contains("\"send\":\"/confirm cb-9\"")
                .contains("\"style\":\"primary\"")
                .contains("\"style\":\"danger\"");
    }

    @Test
    void reusesExistingOutreachSessionWithoutMinting() {
        when(ledger.findByTenantIdAndSenderIdAndDedupKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(outreachSessions.findByTenantIdAndEntryPointIdAndRecipientKey(any(), any(), any()))
                .thenReturn(Optional.of(new OutreachSessionEntity(
                        TENANT.value(), ENTRY_POINT, "principal:" + OPERATOR, "sess-existing",
                        "cashier", 1)));

        var receipt = service.ingest(message("closure-b"),
                new OutreachService.OutreachAuth(posPrincipal()));

        assertThat(receipt.sessionIds()).containsExactly("sess-existing");
        verify(engine, never()).start(any(), any());
        verify(recorder).onTurnRecorded(eq(new Ids.SessionId("sess-existing")), any(), any());
    }

    @Test
    void idempotentOnDedupKey() {
        SystemInboundMessageEntity prior = new SystemInboundMessageEntity(
                "outreach-prior", TENANT.value(), SENDER.value(), "closure-2026-07-03");
        prior.setSessionId("sess-1");
        when(ledger.findByTenantIdAndSenderIdAndDedupKey(TENANT.value(), SENDER.value(), "closure-2026-07-03"))
                .thenReturn(Optional.of(prior));

        var receipt = service.ingest(message("closure-2026-07-03"),
                new OutreachService.OutreachAuth(posPrincipal()));

        assertThat(receipt.disposition()).isEqualTo(OutreachService.Disposition.DUPLICATE);
        assertThat(receipt.id().value()).isEqualTo("outreach-prior");
        verify(recorder, never()).onTurnRecorded(any(), any(), any());
        verify(chatInbox, never()).save(any());
    }

    @Test
    void rejectsDisabledSender() {
        when(senders.find(TENANT, SENDER)).thenReturn(Optional.of(disabledSender()));

        assertThatThrownBy(() -> service.ingest(message("x"),
                new OutreachService.OutreachAuth(posPrincipal())))
                .isInstanceOf(OutreachException.class)
                .extracting(e -> ((OutreachException) e).kind())
                .isEqualTo(OutreachException.Kind.SENDER_DISABLED);
    }

    /* ------------------------------ fixtures ----------------------------- */

    private SystemInboundMessage message(String dedupKey) {
        return new SystemInboundMessage(TENANT, SENDER,
                new OutreachRecipient.ByPrincipal(OPERATOR),
                "cash closed: 1000", java.util.List.of(),
                new Correlation("Cash Register #3", "cb-77", "cash closure 2026-07-03 18:00"),
                dedupKey, Map.of());
    }

    private SystemSender enabledSender() {
        return sender(true);
    }

    private SystemSender disabledSender() {
        return sender(false);
    }

    private SystemSender sender(boolean enabled) {
        return new SystemSender(SENDER, TENANT, ENTRY_POINT, "Cash Register #3", enabled,
                new SenderTrust("shared-secret-jwt", Set.of(), ""), MediationMode.VERBATIM,
                Optional.empty(), AddressingPolicy.principalsOnly(), Optional.empty(),
                Optional.empty(), Instant.now(), Instant.now());
    }

    private ChatEntryPointEntity entryPointEntity() {
        return new ChatEntryPointEntity(ENTRY_POINT, TENANT.value(), "cashdesk", "cashier",
                1, "Cash Desk", "https://logo", "{}", "ACTIVE");
    }

    private Session session(String id) {
        return new Session(new Ids.SessionId(id), TENANT, posPrincipal(),
                new Ids.ProfileId("cashier", 1), "chat", List.of(),
                IntentFrame.empty(), Map.of(), Instant.now(), null);
    }

    private Principal posPrincipal() {
        return new Principal("pos-service", TENANT, Set.of(SenderTrust.SEND_SCOPE),
                Map.of("iss", "pos-issuer"), Instant.now());
    }
}
