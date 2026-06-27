package com.yayatechandinnovations.yayaagentic.auth.stepup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelSessionElevationEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelSessionElevationRepository;
import com.yayatechandinnovations.yayaagentic.persistence.StepUpAuditEntity;
import com.yayatechandinnovations.yayaagentic.persistence.StepUpAuditRepository;
import com.yayatechandinnovations.yayaagentic.persistence.StepUpPendingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.StepUpPendingRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StepUpCoordinatorTest {

    private final StepUpPendingRepository pending = mock(StepUpPendingRepository.class);
    private final StepUpAuditRepository audit = mock(StepUpAuditRepository.class);
    private final ChannelSessionElevationRepository elevations =
            mock(ChannelSessionElevationRepository.class);

    private final Ids.TenantId tenant = new Ids.TenantId("acme");
    private final Ids.SessionId session = new Ids.SessionId("sess-1");
    private final StepUpHint hint = new StepUpHint(
            new StepUpMethod.OtpEmail(),
            "tool:transfer_funds",
            Duration.ofMinutes(10),
            Map.of());

    private final StubProvider provider = new StubProvider();

    private StepUpCoordinator coordinator() {
        when(pending.findOpenForAudience(anyString(), anyString())).thenReturn(Optional.empty());
        when(pending.findAllOpenForSession(anyString())).thenReturn(List.of());
        // Echo back saved entities with an incremented id so callers can read getId()
        AtomicLong nextId = new AtomicLong(1);
        when(pending.save(any())).thenAnswer(inv -> {
            StepUpPendingEntity p = inv.getArgument(0);
            return spyWithId(p, nextId.getAndIncrement());
        });
        when(audit.save(any())).thenAnswer(inv -> {
            StepUpAuditEntity a = inv.getArgument(0);
            return auditSpyWithId(a, nextId.getAndIncrement());
        });
        when(audit.countFailedSince(anyString(), any())).thenReturn(0L);
        return new StepUpCoordinator(pending, audit, elevations,
                new ObjectMapper(), List.of(provider));
    }

    @Test
    void handle_deny_with_no_provider_returns_unrecoverable() {
        when(pending.findOpenForAudience(anyString(), anyString())).thenReturn(Optional.empty());
        when(audit.save(any())).thenAnswer(inv -> auditSpyWithId(inv.getArgument(0), 1L));
        when(audit.countFailedSince(anyString(), any())).thenReturn(0L);
        StepUpCoordinator c = new StepUpCoordinator(pending, audit, elevations,
                new ObjectMapper(), List.of());   // no providers
        var ctx = new StepUpCoordinator.HandleContext(
                tenant, session, null, "whatsapp:+15551234567",
                "alice@acme.example", "a***@acme.example",
                "Please verify it's you", hint);
        StepUpCoordinator.HandleOutcome outcome = c.handleDeny(ctx);
        assertThat(outcome).isInstanceOf(StepUpCoordinator.HandleOutcome.Unrecoverable.class);
    }

    @Test
    void handle_deny_in_cool_off_window_returns_cool_off() {
        when(audit.countFailedSince(anyString(), any())).thenReturn(3L); // ≥ limit
        when(audit.save(any())).thenAnswer(inv -> auditSpyWithId(inv.getArgument(0), 1L));
        StepUpCoordinator c = new StepUpCoordinator(pending, audit, elevations,
                new ObjectMapper(), List.of(provider));
        var ctx = new StepUpCoordinator.HandleContext(
                tenant, session, null, "whatsapp:+15551234567",
                "alice@acme.example", "a***@acme.example",
                "Please verify it's you", hint);
        StepUpCoordinator.HandleOutcome outcome = c.handleDeny(ctx);
        assertThat(outcome).isInstanceOf(StepUpCoordinator.HandleOutcome.CoolOff.class);
        // Provider must not be invoked when cool-off engages.
        assertThat(provider.startCalls).isZero();
    }

    @Test
    void handle_deny_happy_path_persists_pending_and_returns_prompt() {
        var ctx = new StepUpCoordinator.HandleContext(
                tenant, session, null, "whatsapp:+15551234567",
                "alice@acme.example", "a***@acme.example",
                "Please verify it's you", hint);
        StepUpCoordinator.HandleOutcome outcome = coordinator().handleDeny(ctx);
        assertThat(outcome).isInstanceOf(StepUpCoordinator.HandleOutcome.Pending.class);
        var p = (StepUpCoordinator.HandleOutcome.Pending) outcome;
        assertThat(p.maskedTargetAddress()).isEqualTo("a***@acme.example");
        assertThat(p.userPromptHint()).isEqualTo("test prompt");
        verify(pending, times(1)).save(any());
        verify(audit, times(1)).save(any());
    }

    @Test
    void verify_otp_with_no_pending_returns_no_pending() {
        StepUpCoordinator c = coordinator();
        var outcome = c.verifyOtp(new StepUpCoordinator.VerifyContext(
                tenant, session, "whatsapp:+15551234567", "123456",
                Duration.ofHours(1)));
        assertThat(outcome).isInstanceOf(StepUpCoordinator.VerifyOutcome.NoPending.class);
    }

    @Test
    void verify_otp_happy_path_marks_pending_verified_and_writes_elevation() {
        StepUpPendingEntity row = openRow();
        when(pending.findAllOpenForSession(session.value())).thenReturn(List.of(row));
        when(elevations.findBySessionId(session.value())).thenReturn(Optional.empty());
        when(elevations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pending.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(audit.save(any())).thenAnswer(inv -> auditSpyWithId(inv.getArgument(0), 1L));

        provider.willVerifySucceed = true;
        provider.elevatedSubject = "u_42";
        provider.addedScopes = Set.of("stepup:tool:transfer_funds");
        provider.addedClaims = Map.of("stepup.audience", "tool:transfer_funds");

        StepUpCoordinator c = new StepUpCoordinator(pending, audit, elevations,
                new ObjectMapper(), List.of(provider));
        var outcome = c.verifyOtp(new StepUpCoordinator.VerifyContext(
                tenant, session, "whatsapp:+15551234567", "123456",
                Duration.ofHours(1)));
        assertThat(outcome).isInstanceOf(StepUpCoordinator.VerifyOutcome.Verified.class);
        assertThat(row.getCompletion()).isEqualTo("verified");
        verify(elevations, times(1)).save(any());
    }

    @Test
    void verify_otp_wrong_code_increments_attempts_and_keeps_pending_open() {
        StepUpPendingEntity row = openRow();
        when(pending.findAllOpenForSession(session.value())).thenReturn(List.of(row));
        when(pending.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(audit.save(any())).thenAnswer(inv -> auditSpyWithId(inv.getArgument(0), 1L));
        provider.willVerifySucceed = false;
        provider.failureReason = "code_mismatch";

        StepUpCoordinator c = new StepUpCoordinator(pending, audit, elevations,
                new ObjectMapper(), List.of(provider));
        var outcome = c.verifyOtp(new StepUpCoordinator.VerifyContext(
                tenant, session, "whatsapp:+15551234567", "654321",
                Duration.ofHours(1)));
        assertThat(outcome).isInstanceOf(StepUpCoordinator.VerifyOutcome.WrongCode.class);
        assertThat(row.getCompletion()).isNull();   // still open
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    void verify_otp_exhausts_after_max_attempts() {
        StepUpPendingEntity row = openRow();
        // Simulate a row with 4 prior fails — this attempt makes 5.
        row.setAttempts(4);
        when(pending.findAllOpenForSession(session.value())).thenReturn(List.of(row));
        when(pending.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(audit.save(any())).thenAnswer(inv -> auditSpyWithId(inv.getArgument(0), 1L));
        provider.willVerifySucceed = false;
        provider.failureReason = "code_mismatch";

        StepUpCoordinator c = new StepUpCoordinator(pending, audit, elevations,
                new ObjectMapper(), List.of(provider));
        var outcome = c.verifyOtp(new StepUpCoordinator.VerifyContext(
                tenant, session, "whatsapp:+15551234567", "654321",
                Duration.ofHours(1)));
        assertThat(outcome).isInstanceOf(StepUpCoordinator.VerifyOutcome.Exhausted.class);
        assertThat(row.getCompletion()).isEqualTo("exhausted");
    }

    @Test
    void verify_otp_marks_expired_row_and_skips_provider() {
        StepUpPendingEntity row = openRow();
        // Forge an expired row.
        try {
            java.lang.reflect.Field f = StepUpPendingEntity.class.getDeclaredField("expiresAt");
            f.setAccessible(true);
            f.set(row, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        when(pending.findAllOpenForSession(session.value())).thenReturn(List.of(row));
        when(pending.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(audit.save(any())).thenAnswer(inv -> auditSpyWithId(inv.getArgument(0), 1L));

        StepUpCoordinator c = new StepUpCoordinator(pending, audit, elevations,
                new ObjectMapper(), List.of(provider));
        var outcome = c.verifyOtp(new StepUpCoordinator.VerifyContext(
                tenant, session, "whatsapp:+15551234567", "123456",
                Duration.ofHours(1)));
        assertThat(outcome).isInstanceOf(StepUpCoordinator.VerifyOutcome.NoPending.class);
        assertThat(row.getCompletion()).isEqualTo("expired");
        assertThat(provider.verifyCalls).isZero();
    }

    @Test
    void record_playground_pre_elevation_writes_row_and_audit() {
        when(elevations.findBySessionId(session.value())).thenReturn(Optional.empty());
        when(elevations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(audit.save(any())).thenAnswer(inv -> auditSpyWithId(inv.getArgument(0), 1L));

        StepUpCoordinator c = coordinator();
        var row = c.recordPlaygroundPreElevation(
                tenant, session,
                "u_42", "tool:transfer_funds",
                Set.of("stepup:tool:transfer_funds"),
                Map.of("stepup.audience", "tool:transfer_funds"),
                Duration.ofMinutes(15));

        assertThat(row.getElevatedSubject()).isEqualTo("u_42");
        assertThat(row.getAddedScopes()).contains("stepup:tool:transfer_funds");
        assertThat(row.getMethod()).isEqualTo("playground_pre_elevation");
        verify(elevations, times(1)).save(any());
        verify(audit, times(1)).save(any());
    }

    @Test
    void record_playground_pre_elevation_caps_ttl_with_sane_default() {
        when(elevations.findBySessionId(session.value())).thenReturn(Optional.empty());
        when(elevations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(audit.save(any())).thenAnswer(inv -> auditSpyWithId(inv.getArgument(0), 1L));

        StepUpCoordinator c = coordinator();
        var row = c.recordPlaygroundPreElevation(
                tenant, session, "u_42", "tool:x",
                Set.of("stepup:tool:x"), Map.of(),
                null);                                  // null TTL → 1h default
        assertThat(row.getExpiresAt()).isAfter(java.time.OffsetDateTime.now(ZoneOffset.UTC));
    }

    @Test
    void record_playground_pre_elevation_merges_with_existing_row() {
        var existing = new com.yayatechandinnovations.yayaagentic.persistence.ChannelSessionElevationEntity(
                session.value(), tenant.value(), "u_42",
                new String[] {"stepup:tool:read"},
                "{}", "tool:read", "otp_email",
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30), 1L);
        when(elevations.findBySessionId(session.value())).thenReturn(Optional.of(existing));
        when(elevations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(audit.save(any())).thenAnswer(inv -> auditSpyWithId(inv.getArgument(0), 1L));

        StepUpCoordinator c = coordinator();
        var row = c.recordPlaygroundPreElevation(
                tenant, session, "u_42", "tool:write",
                Set.of("stepup:tool:write"), Map.of(),
                Duration.ofMinutes(30));

        // Pre-elevation merges new scope on top of existing scope.
        assertThat(row.getAddedScopes()).contains(
                "stepup:tool:read", "stepup:tool:write");
    }

    @Test
    void active_elevation_returns_empty_when_row_expired() {
        ChannelSessionElevationEntity row = new ChannelSessionElevationEntity(
                "sess-1", "acme", "u_42",
                new String[] {"stepup:tool:x"}, "{}", "tool:x", "otp_email",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1), null);
        when(elevations.findBySessionId("sess-1")).thenReturn(Optional.of(row));
        StepUpCoordinator c = coordinator();
        assertThat(c.activeElevation(session)).isEmpty();
    }

    // ---- helpers ----------------------------------------------------------

    private StepUpPendingEntity openRow() {
        StepUpPendingEntity row = new StepUpPendingEntity(
                "sess-1", "acme", "tool:transfer_funds", "otp_email",
                "$2a$hash", null, "a***@acme.example", 5,
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        try {
            java.lang.reflect.Field f = StepUpPendingEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(row, 99L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return row;
    }

    private static StepUpPendingEntity spyWithId(StepUpPendingEntity p, long id) {
        try {
            java.lang.reflect.Field f = StepUpPendingEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(p, id);
        } catch (Exception e) { /* swallow */ }
        return p;
    }

    private static StepUpAuditEntity auditSpyWithId(StepUpAuditEntity a, long id) {
        try {
            java.lang.reflect.Field f = StepUpAuditEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(a, id);
        } catch (Exception e) { /* swallow */ }
        return a;
    }

    static class StubProvider implements StepUpProvider {
        int startCalls;
        int verifyCalls;
        boolean willVerifySucceed = false;
        String elevatedSubject = "u_test";
        Set<String> addedScopes = Set.of();
        Map<String, Object> addedClaims = Map.of();
        String failureReason = "code_mismatch";

        @Override public StepUpMethod method() { return new StepUpMethod.OtpEmail(); }
        @Override public StartResult start(StepUpStartContext ctx) {
            startCalls++;
            // Mirror the real OtpEmailProvider: the provider masks before
            // putting the address on the pending row.
            String masked = ctx.targetAddress() == null ? "***"
                    : ctx.targetAddress().charAt(0) + "***"
                            + ctx.targetAddress().substring(ctx.targetAddress().indexOf('@'));
            return new StartResult("$2a$hash", "", masked,
                    "test prompt", java.util.Optional.empty(),
                    Instant.now().plus(Duration.ofMinutes(5)));
        }
        @Override public VerifyResult verify(StepUpVerifyContext ctx) {
            verifyCalls++;
            if (willVerifySucceed) {
                return VerifyResult.success(elevatedSubject, addedScopes, addedClaims, "code_verified");
            }
            return VerifyResult.failure(failureReason);
        }
    }
}
