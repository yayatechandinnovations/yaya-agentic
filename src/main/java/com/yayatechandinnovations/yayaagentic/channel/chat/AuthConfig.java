package com.yayatechandinnovations.yayaagentic.channel.chat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * How an authenticated entry point obtains a verified principal
 * ({@code chat-channel-design.md} §8). Persisted as JSONB with a {@code kind}
 * discriminator, mirroring {@code RoutingRule}, so the column stays inspectable
 * from {@code psql}.
 *
 * <p>The trust root is never here: {@link Oidc} only tells the <em>app</em>
 * which issuer to log the user in against; verification still runs through the
 * platform's per-tenant {@code tenant_oidc_issuers} / {@code tenant_jwt_secrets}
 * trust. {@link ResolverExchange} reuses the channel-auth {@code HttpAuthResolver}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AuthConfig.None.class,             name = "None"),
        @JsonSubTypes.Type(value = AuthConfig.Oidc.class,             name = "Oidc"),
        @JsonSubTypes.Type(value = AuthConfig.ResolverExchange.class, name = "ResolverExchange"),
})
public sealed interface AuthConfig
        permits AuthConfig.None, AuthConfig.Oidc, AuthConfig.ResolverExchange {

    record None() implements AuthConfig {}

    /** Interactive front-channel login: the app logs the user in at the tenant
     *  IdP (PKCE) and passes the resulting JWT as a bearer. */
    record Oidc(String issuer, String audience, List<String> scopes) implements AuthConfig {
        public Oidc {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
    }

    /** Server-side exchange: the app holds an opaque first-party token; the
     *  platform calls the tenant's resolver webhook to mint a JWT. The id points
     *  at a {@code channel_auth_resolvers} row.
     *
     *  <p>{@code senderKey} is the tenant-issued chat-sender code (e.g.
     *  {@code "F0DX-12FF"}) that authorizes resolution the way a phone channel's
     *  {@code senderPhone} does — chat entry points have no sender phone, so the
     *  tenant registers a per-entry-point code and the operator pins it here. It
     *  rides the resolver request as {@code senderKey}. Null for tenants whose
     *  resolver doesn't gate on a sender. */
    record ResolverExchange(Long authResolverId, String senderKey) implements AuthConfig {}
}
