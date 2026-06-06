package com.yayatechandinnovations.yayaagentic.auth.service_token;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Machine-to-machine authentication via HMAC service tokens. Recognises
 * {@code X-Yaya-Service-Token: <token>}. Token format:
 *
 * <pre>
 *   tenantId.subject.expEpochSeconds.scopesCsv.base64UrlHmacSha256
 * </pre>
 *
 * The HMAC is computed over {@code tenantId.subject.exp.scopesCsv} using
 * the tenant's secret from {@code yaya.agentic.auth.service-token.tenant-secrets}.
 * Comparison is constant-time. Empty secret map = service tokens disabled.
 */
@Component
@Order(200)
public class ServiceTokenAuthenticator implements Authenticator {

    public static final String HEADER = "X-Yaya-Service-Token";

    private final Map<String, byte[]> tenantSecrets;

    public ServiceTokenAuthenticator(YayaAgenticProperties props) {
        Map<String, byte[]> secrets = new HashMap<>();
        if (props.auth() != null && props.auth().serviceToken() != null
                && props.auth().serviceToken().tenantSecrets() != null) {
            props.auth().serviceToken().tenantSecrets().forEach((tenantId, secret) -> {
                if (secret != null && !secret.isBlank()) {
                    secrets.put(tenantId, secret.getBytes(StandardCharsets.UTF_8));
                }
            });
        }
        this.tenantSecrets = Map.copyOf(secrets);
    }

    @Override
    public String name() { return "service-token"; }

    @Override
    public Optional<Principal> tryAuthenticate(AuthContext ctx) throws AuthenticationException {
        if (tenantSecrets.isEmpty()) return Optional.empty();
        String token = ctx.headers() == null ? null : firstNonNull(
                ctx.headers().get(HEADER),
                ctx.headers().get(HEADER.toLowerCase(Locale.ROOT)));
        if (token == null || token.isBlank()) return Optional.empty();

        String[] parts = token.split("\\.");
        if (parts.length != 5) throw new AuthenticationException("service-token: malformed");

        String tenantId = parts[0];
        String subject = parts[1];
        String expRaw = parts[2];
        String scopesCsv = parts[3];
        String mac = parts[4];

        byte[] secret = tenantSecrets.get(tenantId);
        if (secret == null) throw new AuthenticationException("service-token: unknown tenant");

        long exp;
        try {
            exp = Long.parseLong(expRaw);
        } catch (NumberFormatException e) {
            throw new AuthenticationException("service-token: bad exp");
        }
        if (Instant.now().getEpochSecond() > exp) {
            throw new AuthenticationException("service-token: expired");
        }

        String payload = tenantId + "." + subject + "." + expRaw + "." + scopesCsv;
        byte[] expected = hmac(secret, payload.getBytes(StandardCharsets.UTF_8));
        byte[] presented;
        try {
            presented = Base64.getUrlDecoder().decode(mac);
        } catch (IllegalArgumentException e) {
            throw new AuthenticationException("service-token: bad mac");
        }
        if (!MessageDigest.isEqual(expected, presented)) {
            throw new AuthenticationException("service-token: signature mismatch");
        }

        Set<String> scopes = scopesCsv.isBlank()
                ? Set.of()
                : Arrays.stream(scopesCsv.split(",")).filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());

        return Optional.of(new Principal(
                subject,
                new Ids.TenantId(tenantId),
                scopes,
                Map.of("auth_method", "service-token", "exp", exp),
                Instant.now()));
    }

    private static byte[] hmac(byte[] key, byte[] msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(msg);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
