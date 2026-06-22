package com.yayatechandinnovations.yayaagentic.auth.oidc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.TenantOidcIssuerEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantOidcIssuerRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks in the trust-source priority for {@link OidcAuthenticator}:
 * (1) per-tenant DB rows, then (2) YAML fallback. Behavioural tests for
 * signature/aud checks belong with a real JWT fixture; this suite covers
 * the bail-out-with-empty paths and the untrusted-issuer error shape,
 * which are the lookup-logic failure modes worth pinning.
 *
 * <p>Cache eviction on {@link TenantOidcIssuersChangedEvent} is also
 * exercised so a new issuer registration is picked up without a restart.</p>
 */
class OidcAuthenticatorTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("lottery-dr");

    private final TenantOidcIssuerRepository repo = mock(TenantOidcIssuerRepository.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void no_authorization_header_yields_empty() throws Exception {
        var auth = newAuthenticator(noYamlIssuers(), noTenantIssuers());

        Optional<?> hit = auth.tryAuthenticate(new AuthContext(TENANT, Map.of(), Optional.empty()));

        assertThat(hit).isEmpty();
    }

    @Test
    void non_bearer_scheme_yields_empty() throws Exception {
        var auth = newAuthenticator(noYamlIssuers(), noTenantIssuers());

        Optional<?> hit = auth.tryAuthenticate(new AuthContext(TENANT,
                Map.of("Authorization", "Basic dXNlcjpwYXNz"), Optional.empty()));

        assertThat(hit).isEmpty();
    }

    @Test
    void bearer_token_that_isnt_a_jwt_yields_empty_so_chain_continues() throws Exception {
        var auth = newAuthenticator(noYamlIssuers(), noTenantIssuers());

        Optional<?> hit = auth.tryAuthenticate(new AuthContext(TENANT,
                Map.of("Authorization", "Bearer opaque-token-not-a-jwt"), Optional.empty()));

        assertThat(hit).isEmpty();
    }

    @Test
    void untrusted_issuer_fails_loud_and_lists_trusted_ones() {
        // Both YAML and DB define one issuer each; a JWT from a third issuer
        // gets a precise error message that names the trusted ones.
        givenTenantIssuer("https://tenant-issuer.test", "https://tenant-issuer.test/jwks.json");
        var auth = newAuthenticator(yamlIssuers("https://yaml-issuer.test"), repo);

        String tokenFromUntrustedIss = signedJwtStubWithIss("https://attacker.test");

        assertThatThrownBy(() -> auth.tryAuthenticate(new AuthContext(TENANT,
                Map.of("Authorization", "Bearer " + tokenFromUntrustedIss),
                Optional.empty())))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("untrusted issuer 'https://attacker.test'")
                .hasMessageContaining("https://tenant-issuer.test")
                .hasMessageContaining("https://yaml-issuer.test");
    }

    @Test
    void tenant_cache_is_evicted_on_change_event() {
        // Round 1: tenant has no issuers in DB → cache populated as empty.
        when(repo.findByTenantId(eq(TENANT.value()))).thenReturn(List.of());
        var auth = newAuthenticator(noYamlIssuers(), repo);

        String token = signedJwtStubWithIss("https://new-issuer.test");
        assertThatThrownBy(() -> auth.tryAuthenticate(new AuthContext(TENANT,
                Map.of("Authorization", "Bearer " + token), Optional.empty())))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("untrusted issuer");

        // Operator registers a new issuer for the tenant. Without eviction,
        // the empty cache would stick around and this would still fail.
        when(repo.findByTenantId(eq(TENANT.value()))).thenReturn(List.of(
                entity("https://new-issuer.test", "https://new-issuer.test/jwks.json")));
        auth.onTenantOidcIssuersChanged(new TenantOidcIssuersChangedEvent(TENANT.value()));

        // Now the lookup hits the newly registered issuer. Signature
        // verification still fails (our stub token isn't really signed) but
        // the error must come from validation, not from "untrusted issuer".
        assertThatThrownBy(() -> auth.tryAuthenticate(new AuthContext(TENANT,
                Map.of("Authorization", "Bearer " + token), Optional.empty())))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageNotContaining("untrusted issuer");
    }

    // ---- Helpers ------------------------------------------------------------

    private OidcAuthenticator newAuthenticator(YayaAgenticProperties props,
                                               TenantOidcIssuerRepository repo) {
        return new OidcAuthenticator(props, repo, json);
    }

    private YayaAgenticProperties noYamlIssuers() {
        return propsWith(List.of());
    }

    private YayaAgenticProperties yamlIssuers(String... issuers) {
        return propsWith(java.util.Arrays.stream(issuers)
                .map(iss -> new YayaAgenticProperties.Auth.Oidc.Issuer(
                        iss, iss + "/jwks.json", List.of()))
                .toList());
    }

    private YayaAgenticProperties propsWith(List<YayaAgenticProperties.Auth.Oidc.Issuer> issuers) {
        return new YayaAgenticProperties(
                null, null, null, null, null, null,
                new YayaAgenticProperties.Auth(
                        false,
                        new YayaAgenticProperties.Auth.Oidc(issuers, Duration.ofMinutes(10), Duration.ofSeconds(30)),
                        new YayaAgenticProperties.Auth.ServiceToken(Map.of()),
                        new YayaAgenticProperties.Auth.DelegatedHost("")),
                null, null, null, null);
    }

    private TenantOidcIssuerRepository noTenantIssuers() {
        when(repo.findByTenantId(eq(TENANT.value()))).thenReturn(List.of());
        return repo;
    }

    private void givenTenantIssuer(String iss, String jwksUri) {
        when(repo.findByTenantId(eq(TENANT.value())))
                .thenReturn(List.of(entity(iss, jwksUri)));
    }

    private static TenantOidcIssuerEntity entity(String iss, String jwksUri) {
        return new TenantOidcIssuerEntity(TENANT.value(), iss, jwksUri, "[]");
    }

    /** A minimal JWT-shaped string with the requested iss claim. Not signed —
     *  used only to exercise the lookup logic before signature verification. */
    private static String signedJwtStubWithIss(String iss) {
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"iss\":\"" + iss + "\",\"sub\":\"user-1\",\"exp\":9999999999}");
        String sig = base64Url("fake-signature");
        return header + "." + payload + "." + sig;
    }

    private static String base64Url(String s) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
