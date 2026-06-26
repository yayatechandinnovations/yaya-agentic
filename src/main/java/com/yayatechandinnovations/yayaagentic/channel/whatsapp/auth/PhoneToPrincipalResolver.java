package com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;

import java.util.Optional;

/**
 * Tenant-implemented SPI that resolves a verified WhatsApp phone number to a
 * tenant-side {@link Principal}. Triggered by {@link
 * DefaultPrincipalPolicy.LookupPrincipal} bindings.
 *
 * <p>Most production tenants implement this against their own user table
 * (a JDBC query against {@code users WHERE phone = ?}). The platform ships a
 * single sample implementation —
 * {@link StaticPhoneToPrincipalResolver} — driven by YAML, for demos and
 * playground scenarios where there is no external store.
 *
 * <p><b>Contract for implementations:</b>
 * <ul>
 *   <li>Return {@code Optional.empty()} when the phone does not map to a known
 *       user. The binding's {@code denyOnUnknown} flag decides whether the
 *       authenticator falls through to {@link DefaultPrincipalPolicy.Anonymous}
 *       or rejects the message.</li>
 *   <li>Return a {@link Principal} whose {@code subject} is the tenant-side
 *       user id. Scopes and claims are merged with the channel's baseline
 *       scopes and reserved claims by the materializer.</li>
 *   <li>NEVER include channel-reserved claims (see {@link
 *       PrincipalPolicyMaterializer#RESERVED_CLAIM_KEYS}). The registry
 *       rejects such Principals at runtime with a clear audit message — they
 *       would silently lose to the channel stamp and cause confusing tenant-
 *       side bugs.</li>
 *   <li>Resolvers run on the inbound dispatch worker, OFF the webhook thread,
 *       so blocking I/O (JDBC, HTTP, cache) is acceptable. Twilio's 15 s ack
 *       window is not at risk.</li>
 *   <li>Implementations should be deterministic for a given (tenant, sender,
 *       phone) triple within the registry's cache TTL. Side effects belong in
 *       a tool, not in a resolver.</li>
 * </ul>
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §5.2.
 */
public interface PhoneToPrincipalResolver {

    /**
     * Stable identifier used by the binding configuration ({@link
     * DefaultPrincipalPolicy.LookupPrincipal#resolverId()}) and surfaced in the
     * admin console dropdown. Convention: kebab-case, tenant-domain prefix,
     * e.g. {@code "retail-customer-lookup"}.
     */
    String id();

    /**
     * Operator-facing one-line description of what this resolver does. Shown
     * next to the id in the admin console dropdown.
     */
    default String description() {
        return id();
    }

    /**
     * Resolve a verified WhatsApp phone to a tenant-side Principal.
     *
     * @param tenant   the tenant the binding belongs to
     * @param sender   the WhatsApp sender that received the message
     * @param e164Phone E.164-formatted phone (always begins with {@code +})
     * @param ctx      additional inbound metadata; never null
     * @return the resolved Principal, or empty when the phone is unknown
     */
    Optional<Principal> resolve(Ids.TenantId tenant,
                                SenderId sender,
                                String e164Phone,
                                ResolutionContext ctx);
}
