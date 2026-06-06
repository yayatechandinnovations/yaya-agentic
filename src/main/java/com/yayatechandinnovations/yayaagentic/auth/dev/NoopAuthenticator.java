package com.yayatechandinnovations.yayaagentic.auth.dev;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * DEV / dev-anonymous authenticator. Always issues a fresh anonymous
 * Principal. The {@code AuthenticatorChain} only consults this as a final
 * fallback when {@code yaya.agentic.auth.allow-anonymous} is true.
 */
@Component
public class NoopAuthenticator implements Authenticator {

    @Override
    public String name() {
        return "noop";
    }

    @Override
    public Optional<Principal> tryAuthenticate(AuthContext ctx) {
        return Optional.of(ctx.alreadyVerified().orElseGet(() -> new Principal(
                "anonymous-" + UUID.randomUUID(),
                ctx.tenant() != null ? ctx.tenant() : new Ids.TenantId("default"),
                Set.of("user.read"),
                Map.of(),
                Instant.now()
        )));
    }
}
