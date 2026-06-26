package com.yayatechandinnovations.yayaagentic.auth.stepup;

import com.yayatechandinnovations.yayaagentic.auth.Authorizer;
import com.yayatechandinnovations.yayaagentic.auth.AuthzContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Configurable {@link Authorizer} that emits {@link AuthzDecision.DenyOfferStepUp}
 * when the action's required scopes overlap with an operator-configured
 * "guarded scopes" list AND the principal lacks the elevated
 * {@code stepup:<audience>} scope.
 *
 * <p>Tenants opt in by configuring rules under
 * {@code yaya.agentic.auth.step-up.rules}:
 *
 * <pre>
 * yaya:
 *   agentic:
 *     auth:
 *       step-up:
 *         rules:
 *           - tenant: acme
 *             guarded-scopes: [tool:transfer_funds]
 *             audience: tool:transfer_funds
 *             method: OtpEmail
 *             elevation-ttl: PT30M
 *             user-safe-reason: "Verify it's you before moving money."
 * </pre>
 *
 * <p>Order — sits AFTER {@code ScopeAuthorizer} ({@code @Order(10)}) so the
 * "you can never do this" denial fires first; step-up is only ever asked for
 * actions the user could otherwise do.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §6.1.
 */
@Component
@Order(20)
public class StepUpScopeAuthorizer implements Authorizer {

    private final StepUpAuthorizerProperties props;
    private final StepUpCoordinator coordinator;

    public StepUpScopeAuthorizer(StepUpAuthorizerProperties props,
                                 StepUpCoordinator coordinator) {
        this.props = props;
        this.coordinator = coordinator;
    }

    @Override
    public AuthzDecision authorize(Principal principal,
                                   PermissionRequirement requirement,
                                   Object args,
                                   AuthzContext ctx) {
        if (requirement == null || requirement.requiredScopes() == null
                || requirement.requiredScopes().isEmpty()) {
            return AuthzDecision.Allow.none();
        }
        if (principal == null || principal.tenant() == null) {
            return AuthzDecision.Allow.none();
        }
        Optional<StepUpAuthorizerProperties.Rule> match = matchRule(
                principal.tenant().value(), requirement.requiredScopes());
        if (match.isEmpty()) {
            return AuthzDecision.Allow.none();
        }
        StepUpAuthorizerProperties.Rule rule = match.get();
        Set<String> have = new HashSet<>(principal.scopes() == null
                ? Set.of() : principal.scopes());
        // The session may have an active step-up elevation row whose scopes
        // aren't on the cached Session.principal yet. Merge them in for the
        // duration of THIS authorize() call so a verified OTP / deep link
        // makes the next tool call go through without forcing the user to
        // start a new conversation.
        if (ctx != null && ctx.sessionId() != null) {
            coordinator.activeElevation(new Ids.SessionId(ctx.sessionId().value()))
                    .ifPresent(elevation -> {
                        String[] scopes = elevation.getAddedScopes();
                        if (scopes != null) have.addAll(Arrays.asList(scopes));
                    });
        }
        String requiredElevation = "stepup:" + rule.audience();
        if (have.contains(requiredElevation)) {
            return AuthzDecision.Allow.none();
        }
        return new AuthzDecision.DenyOfferStepUp(
                rule.userSafeReason(),
                "step-up required: principal " + principal.subject()
                        + " lacks " + requiredElevation
                        + " (rule audience=" + rule.audience()
                        + " method=" + rule.method() + ")",
                new StepUpHint(
                        toMethod(rule.method()),
                        rule.audience(),
                        rule.elevationTtl() == null
                                ? java.time.Duration.ofMinutes(30)
                                : rule.elevationTtl(),
                        Map.of()));
    }

    private Optional<StepUpAuthorizerProperties.Rule> matchRule(
            String tenantId, Collection<String> required) {
        if (props == null || props.getRules() == null) return Optional.empty();
        Set<String> requiredSet = new HashSet<>(required);
        for (StepUpAuthorizerProperties.Rule rule : props.getRules()) {
            if (!tenantId.equals(rule.tenant())) continue;
            for (String guarded : rule.guardedScopes()) {
                if (requiredSet.contains(guarded)) return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    private static StepUpMethod toMethod(String name) {
        if (name == null) return new StepUpMethod.OtpEmail();
        return switch (name) {
            case "OtpEmail"        -> new StepUpMethod.OtpEmail();
            case "OtpSmsSecondary" -> new StepUpMethod.OtpSmsSecondary();
            case "DeepLink"        -> new StepUpMethod.DeepLink();
            default -> {
                if (name.startsWith("Custom:")) {
                    yield new StepUpMethod.Custom(name.substring("Custom:".length()));
                }
                throw new IllegalArgumentException(
                        "unknown step-up method '" + name
                                + "' — expected OtpEmail / OtpSmsSecondary / DeepLink / Custom:<id>");
            }
        };
    }
}
