package com.yayatechandinnovations.yayaidentity.credentials;

import com.yayatechandinnovations.yayaidentity.organizations.Organization;
import com.yayatechandinnovations.yayaidentity.organizations.OrganizationService;
import com.yayatechandinnovations.yayaidentity.tokens.IdentityTokenCustomizer;
import com.yayatechandinnovations.yayaidentity.users.User;
import com.yayatechandinnovations.yayaidentity.users.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * I1c: the two halves of human login. {@link IdentityUserDetailsService} binds a
 * user + Argon2id password for the auth-code form login, and
 * {@link IdentityTokenCustomizer} stamps the {@code org} claim onto the issued
 * access token from the user's membership.
 */
@SpringBootTest
@Testcontainers
class HumanLoginAndClaimsIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("yaya_identity");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("yaya.identity.secrets.encryption-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired UserService users;
    @Autowired OrganizationService orgs;
    @Autowired CredentialService credentials;
    @Autowired IdentityUserDetailsService userDetails;
    @Autowired IdentityTokenCustomizer tokenCustomizer;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void userDetailsBindsUserIdAndArgon2Password() {
        User u = users.createPerson("login@acme.test", null, "Operator");
        credentials.setPassword(u.id(), "correct-horse-battery");
        users.setStatus(u.id(), User.Status.ACTIVE);

        UserDetails details = userDetails.loadUserByUsername("login@acme.test");
        // Principal name is the stable user id (→ token sub), not the email.
        assertThat(details.getUsername()).isEqualTo(u.id().value());
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getPassword()).startsWith("{argon2}");            // NFR-Sec-1
        assertThat(passwordEncoder.matches("correct-horse-battery", details.getPassword())).isTrue();
    }

    @Test
    void invitedUserWithoutActivationIsDisabled() {
        User u = users.createPerson("pending@acme.test", null, "Pending");
        credentials.setPassword(u.id(), "correct-horse-battery");   // still INVITED, not activated
        assertThat(userDetails.loadUserByUsername("pending@acme.test").isEnabled()).isFalse();
    }

    @Test
    void unknownUserRejected() {
        assertThatThrownBy(() -> userDetails.loadUserByUsername("nobody@acme.test"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void accessTokenCarriesOrgClaimFromMembership() {
        Organization org = orgs.create("Claims Org");
        User u = users.createPerson("member@acme.test", null, "Member");
        orgs.addMember(org.id(), u.id());

        JwtEncodingContext ctx = JwtEncodingContext.with(
                        JwsHeader.with(SignatureAlgorithm.RS256),
                        JwtClaimsSet.builder().subject(u.id().value()))
                .principal(new UsernamePasswordAuthenticationToken(u.id().value(), null, List.of()))
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .build();

        tokenCustomizer.customize(ctx);

        assertThat(ctx.getClaims().build().getClaims()).containsEntry("org", org.id().value());
    }

    @Test
    void servicePrincipalGetsNoOrgClaim() {
        // A client-credentials principal (a client id) has no membership → no org claim.
        JwtEncodingContext ctx = JwtEncodingContext.with(
                        JwsHeader.with(SignatureAlgorithm.RS256),
                        JwtClaimsSet.builder().subject("yaya-agentic"))
                .principal(new UsernamePasswordAuthenticationToken("yaya-agentic", null, List.of()))
                .tokenType(OAuth2TokenType.ACCESS_TOKEN)
                .build();

        tokenCustomizer.customize(ctx);

        assertThat(ctx.getClaims().build().getClaims()).doesNotContainKey("org");
    }
}
