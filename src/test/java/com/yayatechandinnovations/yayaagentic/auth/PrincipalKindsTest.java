package com.yayatechandinnovations.yayaagentic.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.yayatechandinnovations.yayaagentic.auth.jwt.SharedSecretJwtAuthenticator;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretRepository;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionTestSupport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An issuer several tenants share is bound to one tenant by its claim pins, and
 * only a pinned issuer can vouch for a principal's kind.
 */
class PrincipalKindsTest {

    private static final String ISS = "install-identity";
    private static final String SECRET = "the-install-signs-every-business-with-this-key-32+";
    private static final Ids.TenantId TENANT = new Ids.TenantId("banca-uno");

    private final TenantJwtSecretRepository repo = mock(TenantJwtSecretRepository.class);
    private final JwtSecretCipher cipher = new JwtSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private final SharedSecretJwtAuthenticator authenticator =
            new SharedSecretJwtAuthenticator(repo, cipher, new ObjectMapper());
    private final PrincipalKinds kinds = new PrincipalKinds(repo, new ObjectMapper());

    private void trust(String kind, String requiredClaimsJson) {
        JwtSecretCipher.Encrypted enc = cipher.encrypt(SECRET);
        TenantJwtSecretEntity row = new TenantJwtSecretEntity(TENANT.value(), ISS, enc.ciphertext(), enc.iv(), "[]");
        row.setPrincipalKind(kind);
        row.setRequiredClaimsJson(requiredClaimsJson);
        when(repo.findByTenantId(TENANT.value())).thenReturn(List.of(row));
    }

    private static String token(String org) {
        Date now = new Date();
        return StructuredCompletionTestSupport.sign(SECRET, new JWTClaimsSet.Builder()
                .issuer(ISS).subject("user-1").claim("org", org)
                .issueTime(now).expirationTime(new Date(now.getTime() + 60_000)).build());
    }

    private Optional<Principal> authenticate(String token) {
        return authenticator.tryAuthenticate(new AuthContext(TENANT,
                Map.of("Authorization", "Bearer " + token), Optional.empty()));
    }

    private static Profile profile(Object principalKinds) {
        return new Profile(new Ids.ProfileId("p", 1), TENANT, "P", "hi", "sp", List.of(), List.of(), null,
                "en", principalKinds == null ? Map.of() : Map.of("principalKinds", principalKinds));
    }

    @Test
    void a_pinned_issuer_only_accepts_tokens_carrying_the_tenants_claim() {
        trust("Idp/staff", "{\"org\":\"org-1\"}");

        assertThat(authenticate(token("org-1"))).isPresent();
        assertThatThrownBy(() -> authenticate(token("org-2")))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("does not belong to tenant");
    }

    @Test
    void an_unpinned_issuer_keeps_accepting_any_token_it_signed() {
        trust(null, null);
        assertThat(authenticate(token("org-2"))).isPresent();
    }

    @Test
    void unreadable_pins_reject_every_token_instead_of_none() {
        trust(null, "{not json");
        assertThatThrownBy(() -> authenticate(token("org-1"))).isInstanceOf(AuthenticationException.class);
    }

    @Test
    void the_kind_comes_from_the_tenants_pinned_issuer_never_from_the_token() {
        trust("Idp/staff", "{\"org\":\"org-1\"}");
        Principal staff = authenticate(token("org-1")).orElseThrow();

        assertThat(kinds.kindsOf(TENANT, staff)).containsExactly("Idp/staff");
        assertThat(kinds.admits(profile(List.of("Idp/staff")), staff)).isTrue();
        assertThat(kinds.admits(profile(List.of("Idp/player")), staff)).isFalse();
        // A profile that declares no kinds has no door check.
        assertThat(kinds.admits(profile(null), staff)).isTrue();

        Principal anonymous = new Principal("anonymous-1", TENANT, Set.of(), Map.of(), Instant.now());
        assertThat(kinds.admits(profile(List.of("Idp/staff")), anonymous)).isFalse();
        assertThat(kinds.admits(profile(null), anonymous)).isTrue();

        // Claiming the kind, or just the issuer, in the token's own claims earns nothing.
        Principal claimsIt = new Principal("x", TENANT, Set.of(),
                Map.of("iss", ISS, "principalKind", "Idp/staff", "org", "org-2"), Instant.now());
        assertThat(kinds.kindsOf(TENANT, claimsIt)).isEmpty();
    }

    @Test
    void an_issuer_that_is_not_pinned_to_the_tenant_cannot_grant_a_kind() {
        trust("Idp/staff", null);
        Principal principal = authenticate(token("org-1")).orElseThrow();
        assertThat(kinds.kindsOf(TENANT, principal)).isEmpty();
    }
}
