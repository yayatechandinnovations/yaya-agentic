package com.yayatechandinnovations.yayasales.platform.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CallerResolverTest {

    @Test
    void jwtWithRolesIsAnOperator() {
        Jwt jwt = jwt().claim("roles", List.of("owner", "sales.manager"))
                .claim("email", "merchant@acme.do").build();

        Caller caller = CallerResolver.fromJwt(jwt);

        assertThat(caller).isInstanceOfSatisfying(Caller.Operator.class, op -> {
            assertThat(op.subject()).isEqualTo("user-1");
            assertThat(op.email()).isEqualTo("merchant@acme.do");
            assertThat(op.roles()).containsExactly("owner", "sales.manager");
        });
    }

    @Test
    void bareJwtIsAShopperPrincipal() {
        Caller caller = CallerResolver.fromJwt(jwt().build());

        assertThat(caller).isInstanceOfSatisfying(Caller.Shopper.class, s -> {
            assertThat(s.subject()).isEqualTo("user-1");
            assertThat(s.issuer()).isEqualTo("https://identity.example/identity");
        });
    }

    @Test
    void emptyRolesListIsStillAShopper() {
        Caller caller = CallerResolver.fromJwt(jwt().claim("roles", List.of()).build());
        assertThat(caller).isInstanceOf(Caller.Shopper.class);
    }

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("user-1")
                .issuer("https://identity.example/identity")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
    }
}
