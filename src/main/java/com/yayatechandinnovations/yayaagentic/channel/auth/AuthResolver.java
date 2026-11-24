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
 * {@code (tenant, sender, callerPhone)} triple. Claims, expiration policy,
 * and scope set live INSIDE the JWT, not in the resolver's return shape.
 * Reserved channel claims ({@code phone}, {@code channel.kind},
 * {@code channel.sender}, ...) are stamped by the channel adapter AFTER
 * verification; a resolver that mints a JWT containing them does not gain
 * anything — the post-verify merge wins.
 *
 * <p><b>Profile selection is the one exception.</b> A resolver that knows
 * <em>who</em> the caller is (VIP tier, staff vs. customer, account state)
 * may also say <em>which profile</em> that caller should talk to, overriding
 * the channel binding's default. That selection travels in the structured
 * {@link ResolvedIdentity} return — NOT as a JWT claim — precisely so the
 * framework can validate it (published + in-tenant + allowlist) and stamp it
 * post-verify as the reserved {@code yaya.profile} claim; a resolver cannot
 * smuggle it through arbitrary JWT body claims. See
 * {@code docs/design/resolver-profile-override-design.md}. Implementations
 * that don't care return {@link ResolvedIdentity#of(String)} (token only) and
 * nothing changes.
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
     * The profile-aware form the framework actually calls. Defaults to
     * wrapping {@link #resolve(ResolveRequest)} with no profile override, so
     * existing token-only resolvers keep working unchanged. A resolver that
     * wants to steer the caller to a specific profile overrides this and
     * returns a populated {@link ResolvedIdentity#profile()}.
     *
     * @return {@code Optional.empty()} for "user not found" (drives
     *         {@code on_unresolved}); otherwise the token plus an optional
     *         profile selection.
     */
    default Optional<ResolvedIdentity> resolveIdentity(ResolveRequest req)
            throws AuthResolverException {
        return resolve(req).map(ResolvedIdentity::of);
    }

    /**
     * A resolved identity: the mandatory token plus an optional profile
     * selection that, when present, overrides the channel binding's default
     * profile for this session (validated + stamped by the framework, never
     * trusted from the JWT body).
     *
     * @param jwt     compact JWT serialization, no {@code Bearer } prefix
     * @param profile empty = "don't touch the bound profile"
     */
    record ResolvedIdentity(String jwt, Optional<ProfileSelection> profile) {
        public ResolvedIdentity {
            if (jwt == null || jwt.isBlank()) {
                throw new IllegalArgumentException("jwt required on a ResolvedIdentity");
            }
            profile = profile == null ? Optional.empty() : profile;
        }

        /** Token-only convenience — the common case, no profile override. */
        public static ResolvedIdentity of(String jwt) {
            return new ResolvedIdentity(jwt, Optional.empty());
        }
    }

    /**
     * A resolver's choice of profile for the caller.
     *
     * @param profileId the profile <em>key</em> (e.g. {@code "retail-vip"}),
     *                  not the version-qualified id
     * @param version   {@code null} = resolve to the latest published version
     *                  at session start (§5.2 "latest"); a concrete value pins it
     */
    record ProfileSelection(String profileId, Integer version) {
        public ProfileSelection {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalArgumentException("profileId required on a ProfileSelection");
            }
        }
    }

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
            Instant requestedAt,
            /** The inbound came in on a platform (shared) sender — only staff may resolve there (shared-sender design §7.5). */
            boolean platformSender
    ) {
        public ResolveRequest(Ids.TenantId tenant, SenderId sender, String e164SenderPhone, String senderKey,
                              String e164CallerPhone, Ids.SessionId session, String channel,
                              ResolveReason reason, Instant requestedAt) {
            this(tenant, sender, e164SenderPhone, senderKey, e164CallerPhone, session, channel, reason,
                    requestedAt, false);
        }
    }

    enum ResolveReason { INITIAL, REFRESH }
}
