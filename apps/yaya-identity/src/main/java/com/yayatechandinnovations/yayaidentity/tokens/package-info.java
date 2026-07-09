/**
 * Tokens module — the OAuth 2.0 / OpenID Connect issuer (design §8 module 8,
 * §9.1). Built on Spring Authorization Server: discovery, JWKS, token endpoint
 * (auth-code+PKCE, client-credentials, refresh, and — at I4 — token-exchange),
 * and userinfo.
 *
 * <p>All signing keys come from the platform {@code KeyRing} via
 * {@link com.yayatechandinnovations.yayaidentity.tokens.KeyRingJwkSource}, so
 * rotation and JWKS stay consistent across the service. The public JWKS this
 * module serves is the exact seam yaya-agentic trusts through
 * {@code tenant_oidc_issuers} — with zero agent code changes (PRD M1 / AC-2).
 */
package com.yayatechandinnovations.yayaidentity.tokens;
