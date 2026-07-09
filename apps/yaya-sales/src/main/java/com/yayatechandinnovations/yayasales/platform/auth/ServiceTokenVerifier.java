package com.yayatechandinnovations.yayasales.platform.auth;

import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies the platform service token yaya-agentic's {@code HttpToolDispatcher}
 * mints for {@code AuthForwarding.SERVICE_TOKEN} tools (agent-side wire format:
 * {@code tenantId.subject.exp.scopes} + "." + HMAC-SHA256(payload), base64url,
 * in the {@code X-Yaya-Service-Token} header). The shared secret is per tenant.
 *
 * <p>The HMAC is checked BEFORE any payload field is parsed, so a forged token
 * never reaches parsing; exp is then enforced. Constant-time comparison.
 */
@Component
public class ServiceTokenVerifier {

    // After HMAC verification: tenant is the first dot segment, exp the last
    // all-digit segment before the scopes tail. Greedy subject match keeps a
    // dotted subject intact; scope names (e.g. sales.cart:add) contain no
    // pure-digit dot segment, so exp anchors unambiguously.
    private static final Pattern PAYLOAD =
            Pattern.compile("^(?<tenant>[^.]+)\\.(?<subject>.*)\\.(?<exp>\\d+)\\.(?<scopes>.*)$");

    private final SalesAuthProperties props;

    public ServiceTokenVerifier(SalesAuthProperties props) {
        this.props = props;
    }

    public Optional<Caller.Service> verify(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        int sigDot = token.lastIndexOf('.');
        if (sigDot <= 0 || sigDot == token.length() - 1) return Optional.empty();
        String payload = token.substring(0, sigDot);
        String sig = token.substring(sigDot + 1);

        // Tenant must be readable pre-verification to pick the secret; the
        // payload is only trusted after the HMAC over ALL of it checks out.
        int firstDot = payload.indexOf('.');
        if (firstDot <= 0) return Optional.empty();
        String tenantId = payload.substring(0, firstDot);
        byte[] secret = secretFor(tenantId);
        if (secret == null) return Optional.empty();

        if (!constantTimeEquals(hmacB64Url(secret, payload), sig)) return Optional.empty();

        Matcher m = PAYLOAD.matcher(payload);
        if (!m.matches()) return Optional.empty();
        long exp = Long.parseLong(m.group("exp"));
        if (Instant.now().getEpochSecond() > exp) return Optional.empty();

        List<String> scopes = m.group("scopes").isBlank()
                ? List.of()
                : Arrays.asList(m.group("scopes").split(","));
        return Optional.of(new Caller.Service(new TenantRef(tenantId), m.group("subject"), scopes));
    }

    private byte[] secretFor(String tenantId) {
        String b64 = props.serviceSecrets().get(tenantId);
        if (b64 == null || b64.isBlank()) return null;
        try {
            return Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String hmacB64Url(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] sig = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
