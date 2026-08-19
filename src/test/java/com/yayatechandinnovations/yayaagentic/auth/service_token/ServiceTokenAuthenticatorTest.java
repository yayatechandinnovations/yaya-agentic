package com.yayatechandinnovations.yayaagentic.auth.service_token;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.TenantServiceTokenSecretEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantServiceTokenSecretRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * DB-first / env-fallback resolution contract of the service-token
 * authenticator (one-click provisioning design, gap A).
 */
class ServiceTokenAuthenticatorTest {

    private static final String ENV_TENANT = "env-tenant";
    private static final String ENV_SECRET = "env-secret-0123456789abcdef0123456789";
    private static final String DB_TENANT = "db-tenant";
    private static final String DB_SECRET = "db-secret-0123456789abcdef01234567890";

    private TenantServiceTokenSecretRepository repo;
    private JwtSecretCipher cipher;
    private ServiceTokenAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        repo = mock(TenantServiceTokenSecretRepository.class);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        cipher = new JwtSecretCipher(Base64.getEncoder().encodeToString(key));

        YayaAgenticProperties props = mock(YayaAgenticProperties.class);
        when(props.auth()).thenReturn(new YayaAgenticProperties.Auth(
                false, null,
                new YayaAgenticProperties.Auth.ServiceToken(Map.of(ENV_TENANT, ENV_SECRET)),
                null));
        authenticator = new ServiceTokenAuthenticator(props, repo, cipher);
    }

    @Test
    void dbRowWinsAndIsCached() throws Exception {
        JwtSecretCipher.Encrypted enc = cipher.encrypt(DB_SECRET);
        when(repo.findById(DB_TENANT)).thenReturn(Optional.of(new TenantServiceTokenSecretEntity(
                DB_TENANT, enc.ciphertext(), enc.iv(), OffsetDateTime.now())));

        Optional<Principal> first = authenticate(token(DB_TENANT, DB_SECRET));
        Optional<Principal> second = authenticate(token(DB_TENANT, DB_SECRET));

        assertThat(first).isPresent();
        assertThat(first.get().tenant().value()).isEqualTo(DB_TENANT);
        assertThat(second).isPresent();
        verify(repo).findById(DB_TENANT);          // exactly once — cached after that
        verifyNoMoreInteractions(repo);
    }

    @Test
    void envMapStillWorksWhenNoDbRow() throws Exception {
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        Optional<Principal> principal = authenticate(token(ENV_TENANT, ENV_SECRET));
        assertThat(principal).isPresent();
        assertThat(principal.get().tenant().value()).isEqualTo(ENV_TENANT);
    }

    @Test
    void unknownTenantRejects() {
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> authenticate(token("nobody", "whatever-secret-whatever-secret!")))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("unknown tenant");
    }

    @Test
    void changeEventEvictsCache() throws Exception {
        when(repo.findById(DB_TENANT)).thenReturn(Optional.empty());
        // Known-absent gets cached (falls through to env map → unknown).
        assertThatThrownBy(() -> authenticate(token(DB_TENANT, DB_SECRET)))
                .isInstanceOf(AuthenticationException.class);

        // Row appears + event fires → next attempt sees it.
        JwtSecretCipher.Encrypted enc = cipher.encrypt(DB_SECRET);
        when(repo.findById(DB_TENANT)).thenReturn(Optional.of(new TenantServiceTokenSecretEntity(
                DB_TENANT, enc.ciphertext(), enc.iv(), OffsetDateTime.now())));
        authenticator.onSecretChanged(new TenantServiceTokenSecretChangedEvent(DB_TENANT));

        assertThat(authenticate(token(DB_TENANT, DB_SECRET))).isPresent();
    }

    @Test
    void wrongSecretRejectsEvenWithDbRow() {
        JwtSecretCipher.Encrypted enc = cipher.encrypt(DB_SECRET);
        when(repo.findById(DB_TENANT)).thenReturn(Optional.of(new TenantServiceTokenSecretEntity(
                DB_TENANT, enc.ciphertext(), enc.iv(), OffsetDateTime.now())));
        assertThatThrownBy(() -> authenticate(token(DB_TENANT, "not-the-secret-not-the-secret!!")))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("signature mismatch");
    }

    // ------------------------------------------------------------------

    private Optional<Principal> authenticate(String token) throws AuthenticationException {
        return authenticator.tryAuthenticate(context(token));
    }

    private static AuthContext context(String token) {
        return new AuthContext(null, Map.of(ServiceTokenAuthenticator.HEADER, token), Optional.empty());
    }

    private static String token(String tenantId, String secret) {
        long exp = Instant.now().getEpochSecond() + 300;
        String payload = tenantId + ".svc." + exp + ".callbacks";
        return payload + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(secret.getBytes(StandardCharsets.UTF_8),
                        payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] key, byte[] msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(msg);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
