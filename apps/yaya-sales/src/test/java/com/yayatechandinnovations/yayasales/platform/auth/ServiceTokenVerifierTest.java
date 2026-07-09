package com.yayatechandinnovations.yayasales.platform.auth;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies against the exact wire format yaya-agentic's HttpToolDispatcher
 * mints for SERVICE_TOKEN forwarding: payload
 * {@code tenantId.subject.exp.scopes} + "." + base64url(HMAC-SHA256(payload)).
 */
class ServiceTokenVerifierTest {

    private static final byte[] SECRET = "shared-secret-material-0123456789".getBytes(StandardCharsets.UTF_8);
    private static final String TENANT = "tenant-a";

    private final ServiceTokenVerifier verifier = new ServiceTokenVerifier(new SalesAuthProperties(
            null, Map.of(TENANT, Base64.getEncoder().encodeToString(SECRET)), null));

    @Test
    void acceptsAValidToken() {
        String token = mint(TENANT, "shopper-123", future(), "sales.cart:add,sales.checkout");

        Optional<Caller.Service> caller = verifier.verify(token);

        assertThat(caller).isPresent();
        assertThat(caller.get().tenant().value()).isEqualTo(TENANT);
        assertThat(caller.get().onBehalfOf()).isEqualTo("shopper-123");
        assertThat(caller.get().scopes()).containsExactly("sales.cart:add", "sales.checkout");
    }

    @Test
    void keepsDottedSubjectsIntact() {
        String token = mint(TENANT, "op:merchant@acme.do", future(), "");

        Optional<Caller.Service> caller = verifier.verify(token);

        assertThat(caller).isPresent();
        assertThat(caller.get().onBehalfOf()).isEqualTo("op:merchant@acme.do");
        assertThat(caller.get().scopes()).isEmpty();
    }

    @Test
    void rejectsExpired() {
        String token = mint(TENANT, "shopper", Instant.now().getEpochSecond() - 5, "s");
        assertThat(verifier.verify(token)).isEmpty();
    }

    @Test
    void rejectsBadSignature() {
        String token = mint(TENANT, "shopper", future(), "s");
        assertThat(verifier.verify(token.substring(0, token.length() - 2) + "xx")).isEmpty();
    }

    @Test
    void rejectsUnknownTenantAndGarbage() {
        assertThat(verifier.verify(mintWith("other-tenant", SECRET))).isEmpty();
        assertThat(verifier.verify("not-a-token")).isEmpty();
        assertThat(verifier.verify("")).isEmpty();
        assertThat(verifier.verify(null)).isEmpty();
    }

    @Test
    void rejectsTokenSignedWithWrongSecret() {
        assertThat(verifier.verify(mintWith(TENANT, "wrong-secret".getBytes(StandardCharsets.UTF_8))))
                .isEmpty();
    }

    private static long future() {
        return Instant.now().getEpochSecond() + 60;
    }

    private static String mintWith(String tenant, byte[] secret) {
        String payload = tenant + ".subject." + future() + ".scope";
        return payload + "." + hmac(secret, payload);
    }

    private static String mint(String tenant, String subject, long exp, String scopes) {
        String payload = tenant + "." + subject + "." + exp + "." + scopes;
        return payload + "." + hmac(SECRET, payload);
    }

    private static String hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
