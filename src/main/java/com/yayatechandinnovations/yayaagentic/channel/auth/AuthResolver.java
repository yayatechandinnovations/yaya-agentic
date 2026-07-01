package com.yayatechandinnovations.yayaagentic.channel.auth;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.Instant;
import java.util.Optional;

/**
 * Resolves a verified channel identity (today: an E.164 phone) to a
 * tenant-issued bearer JWT.
 *
 * <p>Implementations do NOT verify the JWT they return — that is the
 * Authenticator chain's job. They DO produce a token the chain will
 * accept: signed by one of the tenant's registered JWT trust roots
 * ({@code tenant_jwt_secrets} HS-family or {@code tenant_oidc_issuers}
 * RS/ES-family).
 *
 * <p>The contract is intentionally narrow: produce a token for this
 * {@code (tenant, sender, callerPhone)} triple. Claims, profile pinning,
 * expiration policy, scope set — all live INSIDE the JWT, not in the
 * resolver's return shape. Reserved channel claims ({@code phone},
 * {@code channel.kind}, {@code channel.sender}, ...) are stamped by the
 * channel adapter AFTER verification; a resolver that mints a JWT
 * containing them does not gain anything — the post-verify merge wins.
 *
 * <p>Two transports ship in-tree:
 * <ul>
 *   <li>{@code BEAN} — direct dispatch to a tenant {@code @Component}
 *       implementing this interface. For library deployments and the
 *       WhatsApp playground.</li>
 *   <li>{@code HTTP} — POST to {@code tenant.hostBaseUrl + http_path}
 *       with the request body in §6.1 of the design. For SaaS tenants.</li>
 * </ul>
 *
 * <p>See {@code docs/design/channel-auth-resolver-design.md} §5.
 */
public interface AuthResolver {

    /**
     * Tenant-local identifier (e.g. {@code "acme-customer-lookup"}). The
     * binding's {@code auth_resolver_id} column points at the row whose
     * {@code name} matches this. The same identifier may exist in
     * different tenants — uniqueness is enforced on {@code (tenant, name)}.
     */
    String name();

    /** Operator-facing one-line description for the admin UI. */
    default String description() {
        return name();
    }

    /**
     * @return {@code Optional.empty()} when the resolver knows the user
     *         does not exist tenant-side. The binding's {@code on_unresolved}
     *         flag decides whether to proceed as anonymous or drop the
     *         inbound. A successful return is the JWT bytes (compact
     *         serialization, no surrounding quotes / Bearer prefix).
     * @throws AuthResolverException for transport / protocol failures —
     *         timeout, 5xx, malformed response, allowlist denial,
     *         self-auth failure. Distinct from "user not found"; these
     *         surface as auth errors and never as anonymous downgrades.
     */
    Optional<String> resolve(ResolveRequest req) throws AuthResolverException;

    /**
     * @param tenant            the tenant this resolver belongs to
     * @param sender            the channel endpoint (e.g. WhatsApp sender)
     *                          that received the inbound message
     * @param e164SenderPhone   E.164 phone the channel endpoint owns —
     *                          for WhatsApp, the sender's
     *                          {@code phone_e164}. Becomes
     *                          {@code senderPhone} in the HTTP wire body.
     *                          Null for channels without a sender phone (chat).
     * @param senderKey         tenant-issued sender code for channels that have
     *                          no sender phone (chat). Becomes {@code senderKey}
     *                          in the HTTP wire body; omitted when null. Mutually
     *                          exclusive with {@code e164SenderPhone} in practice.
     * @param e164CallerPhone   E.164-formatted phone (always begins with
     *                          {@code +}) of the inbound message author
     * @param session           the yaya session that prompted this resolve.
     *                          Useful for tenant audit logs that want to
     *                          correlate "user X resolved for which
     *                          conversation."
     * @param channel           a short identifier — {@code "whatsapp"},
     *                          {@code "sms"}, etc. — describing the inbound
     *                          channel. Extensible without an SPI change.
     * @param reason            {@link ResolveReason#INITIAL} for the first
     *                          resolve in a session; {@link ResolveReason#REFRESH}
     *                          for proactive or reactive renewal. Treated
     *                          as a hint, not a contract — resolvers who
     *                          don't care can ignore it.
     * @param requestedAt       moment yaya issued the request, used by the
     *                          tenant to reject replay if they enforce skew
     */
    record ResolveRequest(
            Ids.TenantId tenant,
            SenderId sender,
            String e164SenderPhone,
            String senderKey,
            String e164CallerPhone,
            Ids.SessionId session,
            String channel,
            ResolveReason reason,
            Instant requestedAt
    ) {}

    enum ResolveReason { INITIAL, REFRESH }
}
