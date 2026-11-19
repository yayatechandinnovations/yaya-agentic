package com.yayatechandinnovations.yayaagentic.auth.service_token;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.TenantServiceTokenSecretRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
 * the tenant's secret, resolved DB-first ({@code tenant_service_token_secrets},
 * written by the {@code /v1/admin/tenants/{id}/service-token-secret} admin
 * surface) with the static {@code yaya.agentic.auth.service-token.tenant-secrets}
 * config map as fallback for pre-existing deployments. Comparison is
 * constant-time. No DB row AND no map entry = service tokens disabled for
 * that tenant.
 */
@Component
@Order(200)
public class ServiceTokenAuthenticator implements Authenticator {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenAuthenticator.class);

    public static final String HEADER = "X-Yaya-Service-Token";

    private final Map<String, byte[]> envSecrets;
    private final TenantServiceTokenSecretRepository repo;
    private final JwtSecretCipher cipher;

    /** Cache: tenantId → resolved secret (empty = known-absent). Evicted by
     *  {@link TenantServiceTokenSecretChangedEvent}; mirrors the
     *  SharedSecretJwtAuthenticator cache pattern. */
    private final Map<String, Optional<byte[]>> cache = new ConcurrentHashMap<>();

    public ServiceTokenAuthenticator(YayaAgenticProperties props,
                                     TenantServiceTokenSecretRepository repo,
                                     JwtSecretCipher cipher) {
        Map<String, byte[]> secrets = new HashMap<>();
        if (props.auth() != null && props.auth().serviceToken() != null
                && props.auth().serviceToken().tenantSecrets() != null) {
            props.auth().serviceToken().tenantSecrets().forEach((tenantId, secret) -> {
                if (secret != null && !secret.isBlank()) {
                    secrets.put(tenantId, secret.getBytes(StandardCharsets.UTF_8));
                }
            });
        }
        this.envSecrets = Map.copyOf(secrets);
        this.repo = repo;
        this.cipher = cipher;
    }

    @Override
    public String name() { return "service-token"; }

    @Override
    public Optional<Principal> tryAuthenticate(AuthContext ctx) throws AuthenticationException {
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

        byte[] secret = resolveSecret(tenantId);
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

    /**
     * Mint a token the install verifies exactly as {@link #tryAuthenticate}
     * does — the platform calling the tenant back (keyword forwards, the
     * {@code system.reply} tool's cousin). Empty when the tenant has no
     * service-token secret: the caller then has nothing to sign with and must
     * not call.
     */
    public Optional<String> mint(Ids.TenantId tenant, String subject, Set<String> scopes, java.time.Duration ttl) {
        if (tenant == null || subject == null || subject.isBlank() || subject.contains(".")) {
            throw new IllegalArgumentException("tenant and a dot-free subject are required");
        }
        byte[] secret = resolveSecret(tenant.value());
        if (secret == null) return Optional.empty();
        String scopesCsv = scopes == null ? "" : scopes.stream().sorted().collect(Collectors.joining(","));
        long exp = Instant.now().plus(ttl == null ? java.time.Duration.ofSeconds(60) : ttl).getEpochSecond();
        String payload = tenant.value() + "." + subject + "." + exp + "." + scopesCsv;
        String mac = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(secret, payload.getBytes(StandardCharsets.UTF_8)));
        return Optional.of(payload + "." + mac);
    }

    /** DB row first (decrypted, cached), config map fallback; null = unknown. */
    private byte[] resolveSecret(String tenantId) {
        Optional<byte[]> hit = cache.computeIfAbsent(tenantId, this::loadSecret);
        return hit.orElse(envSecrets.get(tenantId));
    }

    private Optional<byte[]> loadSecret(String tenantId) {
        try {
            return repo.findById(tenantId)
                    .map(row -> cipher.decrypt(row.getSecretCiphertext(), row.getSecretIv())
                            .getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            // Cipher misconfigured or row corrupt — fail toward the env map
            // rather than hard-failing every service-token request.
            log.warn("service-token secret load failed for tenant {}: {}", tenantId, ex.getMessage());
            return Optional.empty();
        }
    }

    @EventListener
    public void onSecretChanged(TenantServiceTokenSecretChangedEvent event) {
        if (event == null || event.tenantId() == null) return;
        cache.remove(event.tenantId());
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
