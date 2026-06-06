package com.yayatechandinnovations.yayaagentic.core;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Verified caller identity. Produced by an {@code Authenticator}, consumed by
 * the engine and every {@code Authorizer} in the chain. See design §5.4.
 */
public record Principal(
        String subject,
        Ids.TenantId tenant,
        Set<String> scopes,
        Map<String, Object> claims,
        Instant verifiedAt
) {}
