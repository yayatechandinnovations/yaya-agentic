package com.yayatechandinnovations.yayaagentic.auth.stepup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Operator-tunable rules for {@link StepUpScopeAuthorizer}.
 *
 * <p>Each rule is matched on {@code (tenant, guardedScopes ∩ action's required
 * scopes)}. When matched and the principal lacks {@code stepup:<audience>},
 * the authorizer emits {@code DenyOfferStepUp} carrying the configured method
 * + audience + TTL.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §6.1.
 */
@Component
@ConfigurationProperties("yaya.agentic.auth.step-up")
public class StepUpAuthorizerProperties {

    private List<Rule> rules = List.of();

    public List<Rule> getRules() { return rules; }
    public void setRules(List<Rule> rules) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public static class Rule {
        private String tenant;
        private Set<String> guardedScopes = Set.of();
        private String audience;
        private String method = "OtpEmail";
        private Duration elevationTtl = Duration.ofMinutes(30);
        private String userSafeReason = "Please verify it's you to continue.";

        public String tenant() { return tenant; }
        public Set<String> guardedScopes() { return guardedScopes; }
        public String audience() { return audience; }
        public String method() { return method; }
        public Duration elevationTtl() { return elevationTtl; }
        public String userSafeReason() { return userSafeReason; }

        public void setTenant(String tenant) { this.tenant = tenant; }
        public void setGuardedScopes(Set<String> guardedScopes) {
            this.guardedScopes = guardedScopes == null ? Set.of() : Set.copyOf(guardedScopes);
        }
        public void setAudience(String audience) { this.audience = audience; }
        public void setMethod(String method) {
            this.method = method == null || method.isBlank() ? "OtpEmail" : method;
        }
        public void setElevationTtl(Duration elevationTtl) {
            this.elevationTtl = elevationTtl == null
                    ? Duration.ofMinutes(30) : elevationTtl;
        }
        public void setUserSafeReason(String reason) {
            this.userSafeReason = reason == null || reason.isBlank()
                    ? "Please verify it's you to continue." : reason;
        }
    }
}
