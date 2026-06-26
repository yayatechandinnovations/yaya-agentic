package com.yayatechandinnovations.yayaagentic.auth.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.Authorizer;
import com.yayatechandinnovations.yayaagentic.auth.AuthzContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpHint;
import com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpMethod;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.AuditAuthzRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Defends the W-A4 invariant that {@link AuthzDecision.DenyOfferStepUp}
 * short-circuits the {@link AuthorizerChain} the same way {@link
 * AuthzDecision.Deny} does. A regression here would silently let an action
 * through that the Authorizer wanted gated on step-up.
 */
class AuthorizerChainStepUpTest {

    private static final Principal PRINCIPAL = new Principal(
            "u_42",
            new Ids.TenantId("acme"),
            Set.of(),
            Map.of(),
            Instant.now());

    private static final PermissionRequirement REQ = new PermissionRequirement(
            Set.of(), List.of(), null);

    private static final AuthzContext CTX = new AuthzContext(
            null, null, "trace", Map.of("toolId", "test-tool"));

    @Test
    void chain_short_circuits_on_deny_offer_step_up_and_audits_it() {
        var calls = new AtomicInteger();
        Authorizer stepUpEmitter = (p, r, args, c) -> new AuthzDecision.DenyOfferStepUp(
                "Please verify it's you",
                "scope-insufficient: tool:transfer_funds",
                new StepUpHint(new StepUpMethod.OtpEmail(),
                        "tool:transfer_funds",
                        Duration.ofMinutes(15),
                        Map.of()));
        Authorizer downstream = (p, r, args, c) -> {
            calls.incrementAndGet();
            return AuthzDecision.Allow.none();
        };
        AuditAuthzRepository audit = mock(AuditAuthzRepository.class);
        when(audit.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AuthorizerChain chain = new AuthorizerChain(
                List.of(stepUpEmitter, downstream), audit, new ObjectMapper());

        AuthzDecision decision = chain.authorize(PRINCIPAL, REQ, Map.of(), CTX);

        // Must be the step-up variant returned verbatim — not converted to
        // plain Deny, not swallowed and overridden by the downstream Allow.
        assertThat(decision).isInstanceOf(AuthzDecision.DenyOfferStepUp.class);
        assertThat(decision.isDenied()).isTrue();
        // Downstream MUST NOT have been consulted; the chain short-circuited.
        assertThat(calls.get()).isZero();
    }

    @Test
    void allow_then_deny_offer_step_up_still_short_circuits() {
        Authorizer first = (p, r, args, c) -> AuthzDecision.Allow.none();
        Authorizer second = (p, r, args, c) -> new AuthzDecision.DenyOfferStepUp(
                "Please verify it's you",
                "scope-insufficient",
                new StepUpHint(new StepUpMethod.OtpEmail(),
                        "tool:x",
                        Duration.ofMinutes(15),
                        Map.of()));
        var downstreamCalls = new AtomicInteger();
        Authorizer downstream = (p, r, args, c) -> {
            downstreamCalls.incrementAndGet();
            return AuthzDecision.Allow.none();
        };
        AuditAuthzRepository audit = mock(AuditAuthzRepository.class);
        when(audit.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AuthorizerChain chain = new AuthorizerChain(
                List.of(first, second, downstream), audit, new ObjectMapper());

        AuthzDecision decision = chain.authorize(PRINCIPAL, REQ, Map.of(), CTX);
        assertThat(decision).isInstanceOf(AuthzDecision.DenyOfferStepUp.class);
        assertThat(downstreamCalls.get()).isZero();
    }
}
