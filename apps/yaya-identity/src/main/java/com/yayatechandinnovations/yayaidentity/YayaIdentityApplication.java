package com.yayatechandinnovations.yayaidentity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * yaya-identity — the shared, multi-tenant identity provider (IdP) and
 * authorization service for the {@code apps/} ecosystem.
 *
 * <p>A standalone, separately-deployable application (see {@code apps/README.md}).
 * It imports no yaya-agentic internals and integrates only through published
 * seams: OIDC/OAuth2 + JWKS, the PDP decision API, the admin API, and events.
 *
 * <p>Milestone I0 lays the platform: signing keys, secret cipher, append-only
 * audit, outbox, idempotency, and a JWKS endpoint, all on its own
 * {@code yaya_identity} schema.
 */
@SpringBootApplication
public class YayaIdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(YayaIdentityApplication.class, args);
    }
}
