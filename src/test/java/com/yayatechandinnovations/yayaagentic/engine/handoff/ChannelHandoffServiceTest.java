package com.yayatechandinnovations.yayaagentic.engine.handoff;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.persistence.SessionEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SessionRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TurnRepository;
import com.yayatechandinnovations.yayaagentic.recorder.ConversationRecorder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A hand-off joins two conversations of one signed-in user, performed by that
 * user, verified on this request — not two anonymous sessions sharing a null
 * subject, and not a caller inferred from the rows.
 */
class ChannelHandoffServiceTest {

    private final SessionRepository sessions = mock(SessionRepository.class);
    private final TurnRepository turns = mock(TurnRepository.class);
    private final ConversationRecorder recorder = mock(ConversationRecorder.class);
    private final WorkingMemory workingMemory = mock(WorkingMemory.class);
    private final ConversationEngine engine = mock(ConversationEngine.class);
    private final Authenticator authenticator = mock(Authenticator.class);
    private final ChannelHandoffService service =
            new ChannelHandoffService(sessions, turns, recorder, workingMemory, engine, authenticator);

    private static SessionEntity session(String subject, String channel) {
        return new SessionEntity(UUID.randomUUID(), "t1", subject, "owner", 1, channel,
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));
    }

    private static AuthContext verified(String subject) {
        return new AuthContext(new Ids.TenantId("t1"), Map.of(),
                Optional.of(new Principal(subject, new Ids.TenantId("t1"), Set.of(), Map.of(), Instant.now())));
    }

    @Test
    void two_anonymous_sessions_are_not_the_same_person() {
        SessionEntity target = session(null, "app");
        SessionEntity source = session(null, "whatsapp");
        when(sessions.findById(source.getId())).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service.perform(target, new Ids.SessionId(source.getId().toString()), verified("u-1")))
                .isInstanceOf(HandoffException.class)
                .satisfies(e -> assertThat(((HandoffException) e).code()).isEqualTo(HandoffException.NOT_ALLOWED));
        verify(engine, never()).appendSystemTurn(any(), anyString(), any());
    }

    @Test
    void a_caller_without_a_verified_principal_is_refused_even_on_matching_rows() {
        SessionEntity target = session("u-1", "app");
        SessionEntity source = session("u-1", "whatsapp");
        when(sessions.findById(source.getId())).thenReturn(Optional.of(source));
        AuthContext unverified = new AuthContext(new Ids.TenantId("t1"), Map.of(), Optional.empty());
        when(authenticator.authenticate(any())).thenThrow(new AuthenticationException("no credentials"));

        assertThatThrownBy(() -> service.perform(target, new Ids.SessionId(source.getId().toString()), unverified))
                .isInstanceOf(HandoffException.class)
                .satisfies(e -> assertThat(((HandoffException) e).code()).isEqualTo(HandoffException.NOT_ALLOWED));
        assertThatThrownBy(() -> service.perform(target, new Ids.SessionId(source.getId().toString()), null))
                .isInstanceOf(HandoffException.class);
        assertThatThrownBy(() -> service.perform(target, new Ids.SessionId(source.getId().toString()), verified("u-2")))
                .isInstanceOf(HandoffException.class)
                .satisfies(e -> assertThat(((HandoffException) e).code()).isEqualTo(HandoffException.NOT_ALLOWED));
        verify(engine, never()).appendSystemTurn(any(), anyString(), any());
    }

    @Test
    void a_caller_the_chain_verifies_as_someone_else_is_refused() {
        SessionEntity target = session("u-1", "app");
        SessionEntity source = session("u-1", "whatsapp");
        when(sessions.findById(source.getId())).thenReturn(Optional.of(source));
        AuthContext headersOnly = new AuthContext(new Ids.TenantId("t1"), Map.of("x-end-user", "+1555"), Optional.empty());
        when(authenticator.authenticate(headersOnly))
                .thenReturn(new Principal("whatsapp:+1555", new Ids.TenantId("t1"), Set.of(), Map.of(), Instant.now()));

        assertThatThrownBy(() -> service.perform(target, new Ids.SessionId(source.getId().toString()), headersOnly))
                .isInstanceOf(HandoffException.class)
                .satisfies(e -> assertThat(((HandoffException) e).code()).isEqualTo(HandoffException.NOT_ALLOWED));
        verify(engine, never()).appendSystemTurn(any(), anyString(), any());
    }

    @Test
    void the_channel_identity_the_chain_mints_counts_as_the_verified_owner() {
        // The WhatsApp worker carries signed-webhook headers, not a pre-verified principal.
        SessionEntity target = session("whatsapp:+1555", "whatsapp");
        SessionEntity source = session("whatsapp:+1555", "app");
        when(sessions.findById(source.getId())).thenReturn(Optional.of(source));
        when(recorder.loadTurns(any(), any())).thenReturn(List.of());
        when(engine.appendSystemTurn(any(), anyString(), any())).thenReturn(true);
        AuthContext headersOnly = new AuthContext(new Ids.TenantId("t1"), Map.of("x-end-user", "+1555"), Optional.empty());
        when(authenticator.authenticate(headersOnly))
                .thenReturn(new Principal("whatsapp:+1555", new Ids.TenantId("t1"), Set.of(), Map.of(), Instant.now()));

        ChannelHandoffService.Result result =
                service.perform(target, new Ids.SessionId(source.getId().toString()), headersOnly);

        assertThat(result.fromChannel()).isEqualTo("app");
        verify(engine).appendSystemTurn(eq(new Ids.SessionId(target.getId().toString())), anyString(), any());
    }

    @Test
    void the_verified_owner_of_both_sessions_goes_through() {
        SessionEntity target = session("u-1", "app");
        SessionEntity source = session("u-1", "whatsapp");
        when(sessions.findById(source.getId())).thenReturn(Optional.of(source));
        when(recorder.loadTurns(any(), any())).thenReturn(List.of());
        when(engine.appendSystemTurn(any(), anyString(), any())).thenReturn(true);

        ChannelHandoffService.Result result =
                service.perform(target, new Ids.SessionId(source.getId().toString()), verified("u-1"));

        assertThat(result.fromChannel()).isEqualTo("whatsapp");
        verify(engine).appendSystemTurn(eq(new Ids.SessionId(target.getId().toString())), anyString(), any());
    }
}
