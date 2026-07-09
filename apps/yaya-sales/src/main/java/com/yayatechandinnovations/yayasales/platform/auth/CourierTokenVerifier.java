package com.yayatechandinnovations.yayasales.platform.auth;

import java.util.Optional;

/**
 * Verifies a courier bearer token into a {@link Caller.Courier} (§10.4, §15).
 * Implemented by the fulfillment module's fleet service (SHA-256 of the token →
 * {@code delivery_agents.credential_ref}); declared here so the security chain
 * ({@link CourierTokenFilter}) never depends on a domain module.
 *
 * <p>A match means "this token was issued to this agent" — it does NOT mean the
 * agent may operate: the courier surface gates each endpoint on the agent's
 * KYC status (only APPROVED agents act; §14 courier trust).
 */
public interface CourierTokenVerifier {

    Optional<Caller.Courier> verify(String token);
}
