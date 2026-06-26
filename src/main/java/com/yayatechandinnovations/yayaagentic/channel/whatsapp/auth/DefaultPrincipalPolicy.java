package com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;
import java.util.Set;

/**
 * Per-binding policy for how the {@link
 * com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppPhoneAuthenticator}
 * mints the end-user {@link com.yayatechandinnovations.yayaagentic.core.Principal}
 * from a verified inbound WhatsApp message.
 *
 * <p>{@code Anonymous} reproduces the pre-W-A1 behavior: the Principal carries
 * only the channel reserved claims and {@code channel:whatsapp + channel:read}
 * scopes. Anything more sensitive needs a wider scope, which means choosing one
 * of the other two policies.
 *
 * <p>{@code PhoneAsPrincipal} keeps the phone as the identity and lets the
 * tenant declare additional scopes and claims that ride along with every
 * verified phone. Use when the phone alone is enough authorization for the
 * profile's needs (e.g. "any verified phone may ask about its own shipment").
 *
 * <p>{@code LookupPrincipal} delegates to a tenant-implemented
 * {@code PhoneToPrincipalResolver} (not yet implemented in W-A1 — declared so
 * downstream switches stay exhaustive and the admin form can render the option
 * disabled). Throws {@code UnsupportedOperationException} from the materializer
 * until W-A2 wires the resolver registry.
 *
 * <p>Jackson polymorphism mirrors {@link
 * com.yayatechandinnovations.yayaagentic.channel.binding.RoutingRule}: the
 * {@code kind} discriminator keeps the JSONB blob diff-able from {@code psql}.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §5.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = DefaultPrincipalPolicy.Anonymous.class,        name = "Anonymous"),
        @JsonSubTypes.Type(value = DefaultPrincipalPolicy.PhoneAsPrincipal.class, name = "PhoneAsPrincipal"),
        @JsonSubTypes.Type(value = DefaultPrincipalPolicy.LookupPrincipal.class,  name = "LookupPrincipal"),
})
public sealed interface DefaultPrincipalPolicy
        permits DefaultPrincipalPolicy.Anonymous,
                DefaultPrincipalPolicy.PhoneAsPrincipal,
                DefaultPrincipalPolicy.LookupPrincipal {

    /** Pre-W-A1 behavior: minimal scopes + reserved claims only. */
    record Anonymous() implements DefaultPrincipalPolicy {}

    /**
     * The phone IS the identity; the tenant supplies extra scopes/claims that
     * ride along every verified phone for this binding.
     *
     * @param additionalScopes scopes appended to {@code channel:whatsapp,
     *                         channel:read}; never replaces them. May not
     *                         introduce duplicates of the reserved scopes
     *                         (silently de-duplicated in the materializer).
     * @param additionalClaims claims merged into the Principal BEFORE the
     *                         channel-reserved keys are stamped on top.
     *                         Reserved keys (see
     *                         {@link PrincipalPolicyMaterializer#RESERVED_CLAIM_KEYS})
     *                         are rejected at materialization time — they
     *                         would silently lose to the channel stamp anyway.
     */
    record PhoneAsPrincipal(
            Set<String>           additionalScopes,
            Map<String, Object>   additionalClaims
    ) implements DefaultPrincipalPolicy {

        public PhoneAsPrincipal {
            additionalScopes = additionalScopes == null ? Set.of() : Set.copyOf(additionalScopes);
            additionalClaims = additionalClaims == null ? Map.of() : Map.copyOf(additionalClaims);
        }
    }

    /**
     * Delegates to a tenant {@code PhoneToPrincipalResolver} bean. Declared in
     * W-A1 so the sealed type is forward-compatible and the admin form can show
     * the option disabled with a "coming soon" hint. The materializer throws
     * {@code UnsupportedOperationException} when given this variant until W-A2
     * ships the resolver registry.
     *
     * @param resolverId   bean name of a {@code PhoneToPrincipalResolver}
     * @param denyOnUnknown when true, an unknown phone yields an authentication
     *                      failure; when false, the resolver falls back to
     *                      {@link Anonymous}
     */
    record LookupPrincipal(
            String  resolverId,
            boolean denyOnUnknown
    ) implements DefaultPrincipalPolicy {

        public LookupPrincipal {
            if (resolverId == null || resolverId.isBlank()) {
                throw new IllegalArgumentException("LookupPrincipal.resolverId is required");
            }
        }
    }
}
