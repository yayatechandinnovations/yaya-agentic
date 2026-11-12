package com.yayatechandinnovations.yayaagentic.structured;

import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Date;

import static com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionTestSupport.ISS;
import static com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionTestSupport.SECRET;
import static com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionTestSupport.TENANT;
import static com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionTestSupport.claims;
import static com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionTestSupport.serviceToken;
import static com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionTestSupport.sign;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Admission: the tenant comes from the token, and only a scoped tenant service JWT gets in. */
class StructuredCompletionAuthenticatorTest {

    private final StructuredCompletionTestSupport support =
            new StructuredCompletionTestSupport().trust(TENANT, ISS, SECRET);
    private final StructuredCompletionAuthenticator auth = support.authenticator;

    @Test
    void a_service_token_resolves_its_tenant_from_the_issuer() {
        Principal principal = auth.authenticate("Bearer " + serviceToken());

        assertThat(principal.tenant().value()).isEqualTo(TENANT);
        assertThat(principal.subject()).isEqualTo("service:profile-builder");
    }

    @Test
    void an_issuer_shared_by_two_tenants_resolves_to_the_one_whose_key_signed_the_token() {
        support.trust("other-tenant", ISS, "a-completely-different-shared-secret-of-32+-chars");

        assertThat(auth.authenticate("Bearer " + serviceToken()).tenant().value()).isEqualTo(TENANT);
    }

    @Test
    void no_credentials_is_unauthorized() {
        assertStatus(null, HttpStatus.UNAUTHORIZED);
        assertStatus("", HttpStatus.UNAUTHORIZED);
        assertStatus("Basic dXNlcjpwYXNz", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void an_api_key_or_opaque_bearer_is_unauthorized() {
        assertStatus("Bearer yk_live_abc12345xyz9q7w3e8r2t5y6u1i4o0p7a3", HttpStatus.UNAUTHORIZED);
        assertStatus("Bearer not-a-jwt", HttpStatus.UNAUTHORIZED);
    }

    @Test
    void a_token_signed_with_the_wrong_key_is_unauthorized() {
        String forged = sign("an-attacker-chosen-secret-that-is-long-enough-32+",
                claims(ISS, "structured:complete").build());

        assertStatus("Bearer " + forged, HttpStatus.UNAUTHORIZED);
    }

    @Test
    void an_unknown_issuer_is_unauthorized() {
        String token = sign(SECRET, claims("smart-lottery-identity:nobody", "structured:complete").build());

        assertStatus("Bearer " + token, HttpStatus.UNAUTHORIZED);
    }

    @Test
    void an_expired_token_is_unauthorized() {
        String token = sign(SECRET, claims(ISS, "structured:complete")
                .expirationTime(new Date(System.currentTimeMillis() - 1_000)).build());

        assertStatus("Bearer " + token, HttpStatus.UNAUTHORIZED);
    }

    @Test
    void a_token_without_expiry_or_for_another_audience_is_unauthorized() {
        String noExpiry = sign(SECRET, claims(ISS, "structured:complete").expirationTime(null).build());
        String otherAudience = sign(SECRET, claims(ISS, "structured:complete").audience("someone-else").build());

        assertStatus("Bearer " + noExpiry, HttpStatus.UNAUTHORIZED);
        assertStatus("Bearer " + otherAudience, HttpStatus.UNAUTHORIZED);
    }

    @Test
    void a_valid_tenant_token_without_the_scope_is_forbidden() {
        String chatUser = sign(SECRET, claims(ISS, "PLAYER_QUERY_PUBLIC PLAYER_BETTING").build());
        String noScope = sign(SECRET, claims(ISS, null).build());

        assertStatus("Bearer " + chatUser, HttpStatus.FORBIDDEN);
        assertStatus("Bearer " + noScope, HttpStatus.FORBIDDEN);
    }

    private void assertStatus(String header, HttpStatus expected) {
        assertThatThrownBy(() -> auth.authenticate(header))
                .isInstanceOf(StructuredCompletionException.class)
                .extracting(e -> ((StructuredCompletionException) e).status())
                .isEqualTo(expected);
    }
}
