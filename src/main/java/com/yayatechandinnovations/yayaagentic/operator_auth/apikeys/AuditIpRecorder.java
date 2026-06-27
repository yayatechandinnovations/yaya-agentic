package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Single enforcement point for the audit IP-storage split (§12 / §14 D3). The
 * {@code kind} — not any controller — decides which column is populated:
 *
 * <ul>
 *   <li>{@code USE_DENIED} → full {@code client_ip} (a SOC analyst joins it
 *       against threat-intel feeds).</li>
 *   <li>everything else ({@code CREATE}/{@code REVOKE}/{@code RENAME}/…) →
 *       {@code client_ip_hash = sha256(ip || salt)}; the raw IP is discarded.</li>
 * </ul>
 *
 * Rotating {@code yaya.agentic.audit.ip-hash-salt} is a deliberate
 * audit-continuity break: the same IP hashes differently afterward.
 */
@Component
public class AuditIpRecorder {

    /** (client_ip, client_ip_hash) — exactly one non-null, matching the DB CHECK. */
    public record Columns(String clientIp, byte[] clientIpHash) {}

    private final byte[] salt;

    public AuditIpRecorder(AuditProperties props) {
        this.salt = props.ipHashSaltOrEmpty().getBytes(StandardCharsets.UTF_8);
    }

    public Columns columnsFor(String kind, String ip) {
        if (ip == null || ip.isBlank()) {
            return new Columns(null, null);
        }
        if (ApiKeyAuditWriter.USE_DENIED.equals(kind)) {
            return new Columns(ip, null);
        }
        return new Columns(null, hash(ip));
    }

    private byte[] hash(String ip) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(ip.getBytes(StandardCharsets.UTF_8));
            md.update(salt);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
