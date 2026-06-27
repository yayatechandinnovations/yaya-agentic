package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Audit IP-hash salt (§14 D3). Rotating {@code ipHashSalt} is a deliberate
 * audit-continuity break — the same IP hashes differently afterward.
 */
@ConfigurationProperties(prefix = "yaya.agentic.audit")
public record AuditProperties(String ipHashSalt) {
    public String ipHashSaltOrEmpty() {
        return ipHashSalt == null ? "" : ipHashSalt;
    }
}
