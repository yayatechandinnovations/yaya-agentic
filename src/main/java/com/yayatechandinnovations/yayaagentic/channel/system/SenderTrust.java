package com.yayatechandinnovations.yayaagentic.channel.system;

import java.util.Set;

/**
 * How an integration sender proves it is trusted. We do not invent a new auth
 * stack — {@code authenticatorName} routes to a configured {@link
 * com.yayatechandinnovations.yayaagentic.auth.Authenticator} (shared-secret
 * JWT, API key, OIDC, …). See {@code docs/design/system-messaging-design.md} §4.
 *
 * @param authenticatorName  {@code Authenticator.name()} the sender authenticates with
 *                           (e.g. {@code "shared-secret-jwt"}); blank = accept any in the chain
 * @param requiredScopes     scopes the verified principal must carry beyond the
 *                           baseline {@code system:inbound:send} (§4.1)
 * @param credentialRef      which credential to accept — the {@code iss} of the
 *                           {@code tenant_jwt_secret}, the api key id, or the OIDC
 *                           issuer. Blank = any credential the authenticator trusts.
 */
public record SenderTrust(
        String authenticatorName,
        Set<String> requiredScopes,
        String credentialRef
) {
    /** The baseline scope every ingest request must carry (§4.1). */
    public static final String SEND_SCOPE = "system:inbound:send";

    public SenderTrust {
        authenticatorName = authenticatorName == null ? "" : authenticatorName.trim();
        requiredScopes = requiredScopes == null ? Set.of() : Set.copyOf(requiredScopes);
        credentialRef = credentialRef == null ? "" : credentialRef.trim();
    }

    public static SenderTrust none() {
        return new SenderTrust("", Set.of(), "");
    }
}
