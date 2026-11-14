package com.yayatechandinnovations.yayaagentic.channel.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBinding;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBindingService;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.ChannelSessionService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.TurnChannel;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.StartSessionResult;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelSessionEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelSessionRepository;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review 2R, R5 on SMS ({@code docs/guides/admin-panel-sessions.md} §15): once
 * a code prompt is pending for the conversation, a message that may hold the
 * code is taken out of the stored row whichever way the worker leaves.
 */
class SmsInboundSecretRedactionTest {

    private static final String CODE = "481516";
    private static final String TEXT = "mi código es " + CODE;
    private static final String SID = "SM-secret-1";
    private static final String TENANT = "t-sms";
    private static final String SENDER = "sender-1";
    private static final String PHONE = "+18095550000";
    private static final String SESSION = "11111111-2222-3333-4444-555555555555";

    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    private final InboundMessageRepository inbound = mock(InboundMessageRepository.class);
    private final SmsSenderService senders = mock(SmsSenderService.class);
    private final ChannelBindingService bindings = mock(ChannelBindingService.class);
    private final ChannelSessionRepository sessionRows = mock(ChannelSessionRepository.class);
    private final ConversationEngine engine = mock(ConversationEngine.class);
    private final SmsOutboundDispatcher dispatcher = mock(SmsOutboundDispatcher.class);
    private final ObjectMapper json = new ObjectMapper();

    private InboundMessageEntity row;
    private ChannelSessionEntity open;
    private SmsInboundWorker worker;

    @BeforeEach
    void setUp() {
        row = new InboundMessageEntity(SID, TENANT, SENDER, PHONE, 0,
                "{\"MessageSid\":\"" + SID + "\",\"Body\":\"" + TEXT + "\"}");
        when(inbound.findById(SID)).thenReturn(Optional.of(row));
        SmsSender sender = mock(SmsSender.class);
        when(sender.status()).thenReturn(SmsSenderStatus.ACTIVE);
        when(senders.requireForTenant(any(), any())).thenReturn(sender);
        open = new ChannelSessionEntity(TENANT, SENDER, PHONE, SESSION, OffsetDateTime.now(ZoneOffset.UTC));
        when(sessionRows.findFirstByTenantIdAndSenderIdAndEndUserPhoneAndStatus(TENANT, SENDER, PHONE, "OPEN"))
                .thenReturn(Optional.of(open));
        // A code prompt is pending for that session.
        when(engine.awaitsSecret(eq(new Ids.SessionId(SESSION)), eq(TEXT), eq(TurnChannel.SMS))).thenReturn(true);
        doAnswer(call -> {
            ((Runnable) call.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        worker = new SmsInboundWorker(executor, inbound, senders, bindings,
                new ChannelSessionService(sessionRows), engine, dispatcher, json);
    }

    @Test
    void a_message_dropped_for_lack_of_a_route_keeps_no_copy_of_the_code() {
        when(bindings.findLowestPriorityActiveForEndpoint(any(), any())).thenReturn(Optional.empty());

        worker.schedule(SID);

        assertThat(row.getProcessedAt()).isNotNull();
        assertRedacted();
        verify(engine, never()).send(any(), any(), any());
    }

    @Test
    void a_message_whose_processing_fails_keeps_no_copy_of_the_code() {
        when(bindings.findLowestPriorityActiveForEndpoint(any(), any()))
                .thenThrow(new IllegalStateException("database is away"));

        worker.schedule(SID);

        assertThat(row.getProcessingError()).isEqualTo("database is away");
        assertRedacted();
    }

    @Test
    void a_code_that_arrives_after_the_conversation_idled_out_does_not_open_the_next_one() {
        ChannelBinding binding = mock(ChannelBinding.class);
        when(binding.sessionPolicy()).thenReturn(SessionPolicy.defaults());
        when(binding.profile()).thenReturn(new Ids.ProfileId("p", 1));
        when(bindings.findLowestPriorityActiveForEndpoint(any(), any())).thenReturn(Optional.of(binding));
        // The open conversation is past its idle time: this message starts a new one.
        open.setLastInboundAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(3));
        StartSessionResult started = mock(StartSessionResult.class, RETURNS_DEEP_STUBS);
        when(started.session().id()).thenReturn(new Ids.SessionId("99999999-2222-3333-4444-555555555555"));
        when(engine.start(any(), any())).thenReturn(started);
        when(engine.send(any(), any(), any())).thenReturn(Flux.empty());

        worker.schedule(SID);

        ArgumentCaptor<UserMessage> sent = ArgumentCaptor.forClass(UserMessage.class);
        verify(engine).send(any(), sent.capture(), any());
        assertThat(sent.getValue().text()).doesNotContain(CODE).isEqualTo("(código ingresado)");
        assertRedacted();
        assertThat(row.getProcessingError()).isNull();
    }

    private void assertRedacted() {
        assertThat(row.getRawPayloadJson()).doesNotContain(CODE).contains("(código ingresado)");
        assertThat(row.getBodyRedacted()).isEqualTo("(código ingresado)");
        verify(inbound, org.mockito.Mockito.atLeastOnce()).save(row);
    }
}
