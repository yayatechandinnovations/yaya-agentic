package com.yayatechandinnovations.yayaagentic.auth;

import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Wins the chain when an upstream component has already minted and verified
 * a {@link Principal} and passes it through {@link AuthContext#alreadyVerified()}.
 *
 * <p>The inbound channel dispatch worker is the canonical caller — for
 * bindings configured with an auth resolver it runs the resolver
 * (cache miss → outbound JWT) and verifies the returned token via the
 * existing chain on a temporary {@link AuthContext}, then stamps the
 * channel-reserved claims onto the verified Principal AFTER verification.
 * The result is passed through this authenticator so the engine sees a
 * single coherent identity for the whole session.
 *
 * <p>{@code @Order(50)} sits before {@link
 * com.yayatechandinnovations.yayaagentic.auth.oidc.OidcAuthenticator}
 * ({@code @Order(100)}), the
 * {@link com.yayatechandinnovations.yayaagentic.auth.jwt.SharedSecretJwtAuthenticator}
 * ({@code @Order(150)}), and the channel authenticators — so a worker that
 * has already done verification short-circuits the chain.
 */
@Component
@Order(50)
public class PreVerifiedPrincipalAuthenticator implements Authenticator {

    @Override
    public String name() {
        return "pre-verified";
    }

    @Override
    public Optional<Principal> tryAuthenticate(AuthContext ctx) {
        return ctx == null ? Optional.empty() : ctx.alreadyVerified();
    }
}
