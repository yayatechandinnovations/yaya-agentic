package com.yayatechandinnovations.yayaagentic.outreach.media;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Mints and verifies short-lived, single-purpose media tokens (§10 #2) — the
 * outbound mirror of the inbound egress allow-list. HMAC-SHA256 over
 * {@code tenant|assetId|exp}; no public buckets, no guessable links. Signing key
 * resolves from {@code yaya.agentic.media.signing-key}, falling back to the
 * platform secrets key so a single configured secret suffices.
 */
@Component
public class MediaTokenSigner {

    private static final Logger log = LoggerFactory.getLogger(MediaTokenSigner.class);
    private static final long DEFAULT_TTL_SECONDS = 15 * 60;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] key;

    public MediaTokenSigner(
            @Value("${yaya.agentic.media.signing-key:${yaya.agentic.secrets.encryption-key:}}") String configured) {
        if (configured == null || configured.isBlank()) {
            // Dev fallback: stable across a boot but not across restarts. Set a real
            // key in any environment where links must survive a redeploy.
            log.warn("No media signing key configured (yaya.agentic.media.signing-key) — "
                    + "using an ephemeral dev key; signed media URLs will not survive a restart.");
            this.key = ("dev-media-signing-key-" + Instant.now().getEpochSecond())
                    .getBytes(StandardCharsets.UTF_8);
        } else {
            this.key = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** A token granting read of exactly one asset for ~15 minutes. */
    public String mint(Ids.TenantId tenant, String assetId) {
        long exp = Instant.now().getEpochSecond() + DEFAULT_TTL_SECONDS;
        String payload = tenant.value() + "|" + assetId + "|" + exp;
        String body = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + hmac(body);
    }

    /** Verify + parse; empty when malformed, tampered, or expired. */
    public Optional<Grant> verify(String token) {
        if (token == null) return Optional.empty();
        int dot = token.lastIndexOf('.');
        if (dot <= 0) return Optional.empty();
        String body = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        if (!constantTimeEquals(sig, hmac(body))) return Optional.empty();
        String payload;
        try {
            payload = new String(B64D.decode(body), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        String[] parts = payload.split("\\|", 3);
        if (parts.length != 3) return Optional.empty();
        long exp;
        try {
            exp = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (Instant.now().getEpochSecond() > exp) return Optional.empty();
        return Optional.of(new Grant(new Ids.TenantId(parts[0]), parts[1]));
    }

    private String hmac(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    public record Grant(Ids.TenantId tenant, String assetId) {}
}
