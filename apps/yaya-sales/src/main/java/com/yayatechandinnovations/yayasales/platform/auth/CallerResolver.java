package com.yayatechandinnovations.yayasales.platform.auth;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Maps a verified issuer JWT onto a caller class. An operator token carries a
 * non-empty {@code roles} claim (yaya-identity mints scoped roles for console
 * users); a bare principal token — the shopper JWT the agent forwards under
 * explicit {@code PRINCIPAL_TOKEN} — does not. Service callers never arrive
 * here (their proof is the HMAC header, not a JWT), and couriers hold a
 * yaya-sales-issued token (S5).
 */
public final class CallerResolver {

    private CallerResolver() {}

    public static Caller fromJwt(Jwt jwt) {
        List<String> roles = rolesOf(jwt);
        if (!roles.isEmpty()) {
            return new Caller.Operator(jwt.getSubject(), jwt.getClaimAsString("email"), roles);
        }
        return new Caller.Shopper(jwt.getSubject(),
                jwt.getIssuer() == null ? null : jwt.getIssuer().toString());
    }

    private static List<String> rolesOf(Jwt jwt) {
        Object claim = jwt.getClaim("roles");
        if (claim instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
