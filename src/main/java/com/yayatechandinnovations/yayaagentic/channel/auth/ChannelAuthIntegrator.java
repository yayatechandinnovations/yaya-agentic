package com.yayatechandinnovations.yayaagentic.channel.auth;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.chain.AuthenticatorChain;
import com.yayatechandinnovations.yayaagentic.channel.auth.cache.AuthResolverDispatcher;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBinding;
import com.yayatechandinnovations.yayaagentic.channel.binding.OnOverrideDenied;
import com.yayatechandinnovations.yayaagentic.channel.binding.OnUnresolvedPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppPhoneAuthenticator;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Translates a binding's auth-resolver configuration into a verified
 * {@link Principal} + a JWT to persist on the session. Bridges the
 * {@link AuthResolverDispatcher} (which produces tokens) and the
 * {@link AuthenticatorChain} (which verifies them).
 *
 * <p>Three outcomes mirror {@link Resolved.Decision}: {@code JWT_VERIFIED}
 * for the happy path, {@code ANONYMOUS} for FAQ-mode bindings + the
 * {@code on_unresolved=ANONYMOUS} fallthrough, and {@code REJECT} when
 * the binding explicitly drops messages whose caller isn't recognised.
 *
 * <p>Channel-reserved claims ({@code phone}, {@code channel.sender},
 * {@code channel.kind}, {@code locale}, {@code twilioContactSid}) are
 * always stamped post-verification per design §3 non-negotiable #5 — a
 * misbehaving resolver cannot override them by minting a JWT that
 * includes them.
 *
 * <p>See {@code docs/design/channel-auth-resolver-design.md} §9.
 */
@Component
public class ChannelAuthIntegrator {

    private static final Logger log = LoggerFactory.getLogger(ChannelAuthIntegrator.class);

    /** Reserved claim keys carrying a resolver-selected profile override.
     *  Stamped ONLY from the structured {@link AuthResolver.ProfileSelection}
     *  after JWT verification — never copied from the JWT body (design §3 #5,
     *  {@code resolver-profile-override-design.md} §6.3). */
    public static final String RESERVED_PROFILE_CLAIM = "yaya.profile";
    public static final String RESERVED_PROFILE_VERSION_CLAIM = "yaya.profile.version";

    private final AuthResolverDispatcher dispatcher;
    private final AuthenticatorChain authChain;
    private final ProfileRegistry profileRegistry;

    public ChannelAuthIntegrator(AuthResolverDispatcher dispatcher,
                                 AuthenticatorChain authChain,
                                 @Lazy ProfileRegistry profileRegistry) {
        this.dispatcher = dispatcher;
        this.authChain = authChain;
        this.profileRegistry = profileRegistry;
    }

    /**
     * Per-surface guardrail for a resolver-selected profile override
     * ({@code resolver-profile-override-design.md} §7). An empty
     * {@code allowedProfileKeys} means "any published, in-tenant profile is
     * allowed" — the resolver is tenant-trusted. A non-empty set restricts the
     * override to those keys, for public entry points that want a buggy or
     * compromised resolver kept out of privileged internal profiles.
     */
    public record OverridePolicy(Set<String> allowedProfileKeys, OnOverrideDenied onDenied) {
        public OverridePolicy {
            allowedProfileKeys = allowedProfileKeys == null ? Set.of() : Set.copyOf(allowedProfileKeys);
            onDenied = onDenied == null ? OnOverrideDenied.IGNORE : onDenied;
        }

        /** The default: any published in-tenant profile, silently ignore denials. */
        public static OverridePolicy allowAny() {
            return new OverridePolicy(Set.of(), OnOverrideDenied.IGNORE);
        }

        /** Build from a channel surface's stored config (binding / entry point). */
        public static OverridePolicy of(Set<String> allowlist, OnOverrideDenied onDenied) {
            return new OverridePolicy(allowlist, onDenied);
        }
    }

    /**
     * @param binding         the matched channel binding
     * @param baseCtx         the channel-headers-only AuthContext the
     *                        worker built (no Authorization header)
     * @param session         the engine session id, when known. May be null
     *                        on fresh sessions where engine.start hasn't
     *                        run yet; the audit row tolerates null
     * @param sender          channel endpoint receiving the inbound
     * @param senderPhone     E.164 phone the channel endpoint owns
     * @param callerPhone     E.164 phone of the inbound message author
     * @return the verified Principal + JWT (or empty), and the decision
     *         the caller honors
     */
    public Resolved resolve(ChannelBinding binding,
                            AuthContext baseCtx,
                            Ids.SessionId session,
                            SenderId sender,
                            String senderPhone,
                            String callerPhone) {
        if (binding.authResolverId() == null) {
            // FAQ-mode binding: no resolver configured. The chain's
            // WhatsAppPhoneAuthenticator (@Order 900) will mint the
            // baseline Principal on engine.start.
            return Resolved.anonymous(baseCtx,
                    "no auth resolver on this binding (FAQ mode) — anonymous principal");
        }
        return resolveFor(baseCtx.tenant(), binding.authResolverId(), binding.onUnresolved(),
                baseCtx, session, sender, senderPhone, null, callerPhone, "whatsapp",
                WhatsAppPhoneAuthenticator.BASELINE_SCOPES,
                WhatsAppPhoneAuthenticator.channelClaims(baseCtx),
                OverridePolicy.of(binding.allowedOverrideProfiles(), binding.onOverrideDenied()));
    }

    /**
     * Channel-agnostic core: dispatch the resolver, verify the returned JWT
     * through the chain, and stamp channel-reserved claims — used by every
     * channel (WhatsApp/SMS via {@link #resolve}, chat via the public front
     * door). The claim-stamping is the only per-channel concern, so the
     * caller supplies {@code baselineScopes} + {@code channelClaims}; the
     * dispatch → verify pipeline is identical everywhere.
     *
     * @param onUnresolved  what to do when the resolver returns 404 / fails —
     *                      {@code REJECT} (fail closed) or {@code ANONYMOUS}
     * @param senderKey     tenant-issued sender code for channels with no sender
     *                      phone (chat); null for phone channels
     * @param channel       short channel kind id ({@code "whatsapp"} / {@code "chat"})
     * @param baselineScopes scopes unioned into the verified Principal
     * @param channelClaims  claims that override the JWT's (§3 #5)
     */
    public Resolved resolveFor(Ids.TenantId tenant,
                               long authResolverId,
                               OnUnresolvedPolicy onUnresolved,
                               AuthContext baseCtx,
                               Ids.SessionId session,
                               SenderId sender,
                               String senderPhone,
                               String senderKey,
                               String callerPhone,
                               String channel,
                               Set<String> baselineScopes,
                               Map<String, Object> channelClaims) {
        return resolveFor(tenant, authResolverId, onUnresolved, baseCtx, session, sender,
                senderPhone, senderKey, callerPhone, channel, baselineScopes, channelClaims,
                AuthResolver.ResolveReason.INITIAL, OverridePolicy.allowAny());
    }

    /**
     * As the 12-arg {@link #resolveFor}, but with an explicit per-surface
     * {@link OverridePolicy} governing any resolver-selected profile override.
     * INITIAL reason. Used by the WhatsApp/chat front doors once they carry a
     * binding/entry-point allowlist.
     */
    public Resolved resolveFor(Ids.TenantId tenant,
                               long authResolverId,
                               OnUnresolvedPolicy onUnresolved,
                               AuthContext baseCtx,
                               Ids.SessionId session,
                               SenderId sender,
                               String senderPhone,
                               String senderKey,
                               String callerPhone,
                               String channel,
                               Set<String> baselineScopes,
                               Map<String, Object> channelClaims,
                               OverridePolicy overridePolicy) {
        return resolveFor(tenant, authResolverId, onUnresolved, baseCtx, session, sender,
                senderPhone, senderKey, callerPhone, channel, baselineScopes, channelClaims,
                AuthResolver.ResolveReason.INITIAL, overridePolicy);
    }

    /**
     * As {@link #resolveFor(Ids.TenantId, long, OnUnresolvedPolicy, AuthContext,
     * Ids.SessionId, SenderId, String, String, String, String, Set, Map)} but with
     * an explicit resolve {@code reason}. {@link AuthResolver.ResolveReason#REFRESH}
     * bypasses the token cache and re-mints — used by
     * {@link com.yayatechandinnovations.yayaagentic.tool.dispatch.SessionTokenRefresher}
     * when an own-scoped tool call reports the session token is stale (the player
     * registered mid-session and needs the {@code PLAYER_QUERY_OWN} upgrade).
     */
    public Resolved resolveFor(Ids.TenantId tenant,
                               long authResolverId,
                               OnUnresolvedPolicy onUnresolved,
                               AuthContext baseCtx,
                               Ids.SessionId session,
                               SenderId sender,
                               String senderPhone,
                               String senderKey,
                               String callerPhone,
                               String channel,
                               Set<String> baselineScopes,
                               Map<String, Object> channelClaims,
                               AuthResolver.ResolveReason reason) {
        return resolveFor(tenant, authResolverId, onUnresolved, baseCtx, session, sender,
                senderPhone, senderKey, callerPhone, channel, baselineScopes, channelClaims,
                reason, OverridePolicy.allowAny());
    }

    public Resolved resolveFor(Ids.TenantId tenant,
                               long authResolverId,
                               OnUnresolvedPolicy onUnresolved,
                               AuthContext baseCtx,
                               Ids.SessionId session,
                               SenderId sender,
                               String senderPhone,
                               String senderKey,
                               String callerPhone,
                               String channel,
                               Set<String> baselineScopes,
                               Map<String, Object> channelClaims,
                               AuthResolver.ResolveReason reason,
                               OverridePolicy overridePolicy) {
        AuthResolverDispatcher.Result result = dispatcher.dispatch(
                authResolverId,
                tenant,
                sender,
                senderPhone,
                senderKey,
                callerPhone,
                session == null ? new Ids.SessionId(UUID.randomUUID().toString()) : session,
                channel,
                reason);

        return switch (result) {
            case AuthResolverDispatcher.Result.TokenIssued ti ->
                    verifyAndStamp(baseCtx, ti.jwt(), ti.profile(), onUnresolved,
                            overridePolicy, baselineScopes, channelClaims);
            case AuthResolverDispatcher.Result.Unresolved u ->
                    applyOnUnresolved(onUnresolved, baseCtx, null);
            case AuthResolverDispatcher.Result.Failed f ->
                    applyOnUnresolved(onUnresolved, baseCtx,
                            "resolver failed (status=" + f.status() + ")");
        };
    }

    private Resolved verifyAndStamp(AuthContext baseCtx, String jwt,
                                    Optional<AuthResolver.ProfileSelection> profileOverride,
                                    OnUnresolvedPolicy onUnresolved,
                                    OverridePolicy overridePolicy,
                                    Set<String> baselineScopes, Map<String, Object> channelClaims) {
        Map<String, String> mergedHeaders = new LinkedHashMap<>(baseCtx.headers());
        mergedHeaders.put("Authorization", "Bearer " + jwt);
        AuthContext jwtCtx = new AuthContext(
                baseCtx.tenant(), Map.copyOf(mergedHeaders), Optional.empty());

        Optional<Principal> verified;
        try {
            verified = authChain.tryAuthenticate(jwtCtx);
        } catch (AuthenticationException ae) {
            log.warn("auth-resolver JWT rejected by chain for tenant={}: {}",
                    baseCtx.tenant().value(), ae.getMessage());
            return applyOnUnresolved(onUnresolved, baseCtx,
                    "JWT verification failed: " + ae.getMessage());
        }
        if (verified.isEmpty()) {
            log.warn("auth-resolver JWT not recognised by any authenticator (tenant={}) — "
                            + "check tenant_jwt_secrets/tenant_oidc_issuers", baseCtx.tenant().value());
            return applyOnUnresolved(onUnresolved, baseCtx,
                    "no authenticator recognised the JWT");
        }

        // Validate the resolver's profile override against the per-surface
        // guardrails (§7). A denial under REJECT fails the whole inbound closed;
        // under IGNORE it simply doesn't stamp, so the bound profile wins.
        OverrideOutcome override = evaluateOverride(baseCtx.tenant(), profileOverride, overridePolicy);
        if (override instanceof OverrideOutcome.Reject rej) {
            log.warn("profile override rejected for tenant={} ({}): dropping inbound",
                    baseCtx.tenant().value(), rej.auditReason());
            return Resolved.reject("profile override denied: " + rej.auditReason());
        }
        AuthResolver.ProfileSelection applied =
                override instanceof OverrideOutcome.Apply a ? a.selection() : null;
        String deniedReason = override instanceof OverrideOutcome.Ignore ig ? ig.deniedReason() : null;

        Principal stamped = stampChannelClaims(verified.get(), baselineScopes, channelClaims, applied);
        return new Resolved(Resolved.Decision.JWT_VERIFIED,
                Optional.of(stamped), Optional.of(jwt),
                "resolver JWT verified by the trust chain",
                Optional.ofNullable(applied == null ? null : applied.profileId()),
                Optional.ofNullable(deniedReason));
    }

    /**
     * Apply the §7 guardrails to a resolver-selected override: allowlist first
     * (cheap, policy), then published + in-tenant (existence). Returns whether
     * to stamp it, drop it silently, or reject the inbound.
     */
    private OverrideOutcome evaluateOverride(Ids.TenantId tenant,
                                             Optional<AuthResolver.ProfileSelection> override,
                                             OverridePolicy policy) {
        if (override.isEmpty()) return OverrideOutcome.NONE;
        AuthResolver.ProfileSelection sel = override.get();

        Set<String> allowlist = policy.allowedProfileKeys();
        if (!allowlist.isEmpty() && !allowlist.contains(sel.profileId())) {
            return deny(policy, "NOT_ALLOWLISTED", sel.profileId());
        }
        Ids.ProfileId probe = sel.version() == null
                ? Ids.ProfileId.latest(sel.profileId())
                : new Ids.ProfileId(sel.profileId(), sel.version());
        boolean published = (sel.version() == null
                ? profileRegistry.findLatest(tenant, probe)
                : profileRegistry.find(tenant, probe)).isPresent();
        if (!published) {
            return deny(policy, "UNPUBLISHED", sel.profileId()
                    + (sel.version() == null ? "@latest" : "@" + sel.version()));
        }
        return new OverrideOutcome.Apply(sel);
    }

    private OverrideOutcome deny(OverridePolicy policy, String reasonCode, String requested) {
        String audit = reasonCode + " (requested=" + requested + ")";
        return policy.onDenied() == OnOverrideDenied.REJECT
                ? new OverrideOutcome.Reject(audit)
                : new OverrideOutcome.Ignore(audit);   // drop the override, keep the bound profile
    }

    /** Result of validating a resolver-selected profile override. */
    private sealed interface OverrideOutcome {
        OverrideOutcome NONE = new Ignore(null);   // no override at all — null denial reason
        record Apply(AuthResolver.ProfileSelection selection) implements OverrideOutcome {}
        /** @param deniedReason audit reason when an override was dropped; null when there was none */
        record Ignore(String deniedReason) implements OverrideOutcome {}
        record Reject(String auditReason) implements OverrideOutcome {}
    }

    private Resolved applyOnUnresolved(OnUnresolvedPolicy onUnresolved, AuthContext baseCtx,
                                       String reason) {
        // A null reason means the resolver genuinely returned 404 (no account).
        // A non-null reason is the specific failure verifyAndStamp already
        // diagnosed — token returned but not verifiable, JWT rejected by the
        // chain, resolver transport error. Preserve whichever it is so the
        // inspector stops reporting a verification failure as "no account".
        String detail = reason == null ? "resolver returned no account (404)" : reason;
        if (onUnresolved == OnUnresolvedPolicy.ANONYMOUS) {
            return Resolved.anonymous(baseCtx, detail);
        }
        return Resolved.reject(detail);
    }

    /**
     * Merge: keep the JWT's subject, tenant, issuedAt, and claims; override
     * channel-reserved claim keys with the caller-supplied channel claims
     * (§3 #5). Scopes are unioned with the channel's baseline so identity-gated
     * tools still see "this Principal is on the {whatsapp,chat} channel."
     *
     * <p>The reserved {@code yaya.profile}* keys are handled specially: any
     * value the resolver smuggled into the JWT body is stripped, and the claim
     * is (re)stamped ONLY from the validated structured {@code applied}
     * selection. A resolver therefore cannot pick a profile by minting a JWT
     * claim — only through the framework-validated return shape (§6.3).
     */
    private Principal stampChannelClaims(Principal jwtPrincipal,
                                         Set<String> baselineScopes,
                                         Map<String, Object> channelClaims,
                                         AuthResolver.ProfileSelection applied) {
        Set<String> scopes = new HashSet<>(jwtPrincipal.scopes());
        scopes.addAll(baselineScopes);

        Map<String, Object> claims = new LinkedHashMap<>(jwtPrincipal.claims());
        claims.putAll(channelClaims);
        // Strip any resolver-smuggled reserved profile claims BEFORE stamping the
        // validated one — the JWT body is never a trusted source for these.
        claims.remove(RESERVED_PROFILE_CLAIM);
        claims.remove(RESERVED_PROFILE_VERSION_CLAIM);
        if (applied != null) {
            claims.put(RESERVED_PROFILE_CLAIM, applied.profileId());
            if (applied.version() != null) {
                claims.put(RESERVED_PROFILE_VERSION_CLAIM, applied.version());
            }
        }
        // Same null-hostility as the authenticators: Map.copyOf below NPEs on a
        // null value, and a stamped channel claim (or a leftover JWT claim) can
        // be null. A null claim is unusable, so drop it rather than reject.
        claims.values().removeIf(v -> v == null);

        return new Principal(
                jwtPrincipal.subject(),
                jwtPrincipal.tenant(),
                Set.copyOf(scopes),
                Map.copyOf(claims),
                jwtPrincipal.verifiedAt() == null ? Instant.now() : jwtPrincipal.verifiedAt());
    }

    /**
     * Output of {@link #resolve}. The caller composes the final
     * {@link AuthContext} for engine.start from {@link #authContextForEngine()}.
     */
    public record Resolved(Decision decision,
                           Optional<Principal> principal,
                           Optional<String> jwt,
                           String reason,
                           Optional<String> appliedProfileOverride,
                           Optional<String> overrideDeniedReason) {

        public Resolved {
            appliedProfileOverride = appliedProfileOverride == null ? Optional.empty() : appliedProfileOverride;
            overrideDeniedReason = overrideDeniedReason == null ? Optional.empty() : overrideDeniedReason;
        }

        /** 4-arg form for the non-override paths (anonymous / reject / tests). */
        public Resolved(Decision decision, Optional<Principal> principal,
                        Optional<String> jwt, String reason) {
            this(decision, principal, jwt, reason, Optional.empty(), Optional.empty());
        }

        public enum Decision { JWT_VERIFIED, ANONYMOUS, REJECT }

        private static Resolved anonymous(AuthContext baseCtx, String reason) {
            return new Resolved(Decision.ANONYMOUS, Optional.empty(), Optional.empty(), reason);
        }

        private static Resolved reject(String reason) {
            return new Resolved(Decision.REJECT, Optional.empty(), Optional.empty(), reason);
        }

        /**
         * Build the AuthContext the engine should use. For
         * {@code JWT_VERIFIED} the verified {@link Principal} is stamped
         * into {@link AuthContext#alreadyVerified()} so {@link
         * com.yayatechandinnovations.yayaagentic.auth.PreVerifiedPrincipalAuthenticator}
         * short-circuits the chain.
         *
         * <p>For {@code ANONYMOUS} the base context is returned as-is so
         * the WhatsAppPhoneAuthenticator mints the baseline Principal.
         *
         * <p>Throws on {@code REJECT} — the caller must check the decision
         * first.
         *
         * <p>On {@code JWT_VERIFIED} the verified resolver JWT is also
         * re-attached as an {@code Authorization: Bearer} header so the
         * engine can forward it to {@code PRINCIPAL_TOKEN} HTTP tools. The
         * raw header is dropped after verification, so without this the
         * tool dispatcher has no token to forward and the tool endpoint
         * 401s. See {@code docs/design/channel-auth-resolver-design.md} §9.
         */
        public AuthContext authContextForEngine(AuthContext baseCtx) {
            return switch (decision) {
                case JWT_VERIFIED -> {
                    Map<String, String> headers = new LinkedHashMap<>(baseCtx.headers());
                    jwt.ifPresent(j -> headers.put("Authorization", "Bearer " + j));
                    yield new AuthContext(
                            baseCtx.tenant(), Map.copyOf(headers),
                            principal.map(Optional::of).orElse(Optional.empty()));
                }
                case ANONYMOUS -> baseCtx;
                case REJECT -> throw new IllegalStateException(
                        "REJECT decision — do not call authContextForEngine; drop the message instead");
            };
        }
    }
}
