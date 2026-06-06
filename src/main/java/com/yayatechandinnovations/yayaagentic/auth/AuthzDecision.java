package com.yayatechandinnovations.yayaagentic.auth;

import java.util.Map;

/**
 * Decision result. {@code userSafeReason} is what the agent may paraphrase to
 * the user; {@code auditReason} is what we log. They MUST be different — the
 * former never leaks implementation detail. See design §5.5.
 */
public sealed interface AuthzDecision {

    record Allow(Map<String, Object> obligations) implements AuthzDecision {
        public static Allow none() { return new Allow(Map.of()); }
    }

    record Deny(String userSafeReason, String auditReason) implements AuthzDecision {}
}
