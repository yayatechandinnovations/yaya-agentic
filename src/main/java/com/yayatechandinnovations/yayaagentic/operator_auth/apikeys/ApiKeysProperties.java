package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API-key knobs (§14 D1/D2). Deployment-wide single values in v1; per-workspace
 * overrides are an additive follow-up.
 *
 * <ul>
 *   <li>{@code cacheTtlSeconds} — verifier cache TTL. {@code 0} disables the
 *       cache (per-request DB hit for strict revocation).</li>
 *   <li>{@code maxActivePerWorkspace} — active-key cap; the (cap+1)th create
 *       returns 409.</li>
 *   <li>{@code requireSecureTransport} — reject plaintext bearer (also forced
 *       on in the {@code prod} profile).</li>
 *   <li>{@code failedAuthPerMinutePerIp} — brute-force budget per IP.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "yaya.agentic.api-keys")
public record ApiKeysProperties(
        Long cacheTtlSeconds,
        Integer maxActivePerWorkspace,
        Boolean requireSecureTransport,
        Integer failedAuthPerMinutePerIp
) {
    public long cacheTtlSecondsOrDefault() {
        return cacheTtlSeconds == null || cacheTtlSeconds < 0 ? 60L : cacheTtlSeconds;
    }

    public int maxActivePerWorkspaceOrDefault() {
        return maxActivePerWorkspace == null || maxActivePerWorkspace <= 0 ? 100 : maxActivePerWorkspace;
    }

    public boolean requireSecureTransportOrDefault() {
        return requireSecureTransport != null && requireSecureTransport;
    }

    public int failedAuthPerMinutePerIpOrDefault() {
        return failedAuthPerMinutePerIp == null || failedAuthPerMinutePerIp <= 0 ? 20 : failedAuthPerMinutePerIp;
    }
}
