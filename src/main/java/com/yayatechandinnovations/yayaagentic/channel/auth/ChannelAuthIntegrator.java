package com.yayatechandinnovations.yayaagentic.channel.auth;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.chain.AuthenticatorChain;
import com.yayatechandinnovations.yayaagentic.channel.auth.cache.AuthResolverDispatcher;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBinding;
import com.yayatechandinnovations.yayaagentic.channel.binding.OnUnresolvedPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppPhoneAuthenticator;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final AuthResolverDispatcher dispatcher;
    private final AuthenticatorChain authChain;

    public ChannelAuthIntegrator(AuthResolverDispatcher dispatcher,
                                 AuthenticatorChain authChain) {
        this.dispatcher = dispatcher;
        this.authChain = authChain;
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
            return Resolved.anonymous(baseCtx);
        }

        AuthResolverDispatcher.Result result = dispatcher.dispatch(
                binding.authResolverId(),
                baseCtx.tenant(),
                sender,
                senderPhone,
                callerPhone,
                session == null ? new Ids.SessionId(UUID.randomUUID().toString()) : session,
                "whatsapp",
                com.yayatechandinnovations.yayaagentic.channel.auth.AuthResolver.ResolveReason.INITIAL);

        return switch (result) {
            case AuthResolverDispatcher.Result.TokenIssued ti ->
                    verifyAndStamp(baseCtx, ti.jwt(), binding);
            case AuthResolverDispatcher.Result.Unresolved u ->
                    applyOnUnresolved(binding, baseCtx, null);
            case AuthResolverDispatcher.Result.Failed f ->
                    applyOnUnresolved(binding, baseCtx,
                            "resolver failed (status=" + f.status() + ")");
        };
    }

    private Resolved verifyAndStamp(AuthContext baseCtx, String jwt, ChannelBinding binding) {
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
            return applyOnUnresolved(binding, baseCtx,
                    "JWT verification failed: " + ae.getMessage());
        }
        if (verified.isEmpty()) {
            log.warn("auth-resolver JWT not recognised by any authenticator (tenant={}) — "
                            + "check tenant_jwt_secrets/tenant_oidc_issuers", baseCtx.tenant().value());
            return applyOnUnresolved(binding, baseCtx,
                    "no authenticator recognised the JWT");
        }
        Principal stamped = stampChannelClaims(verified.get(), baseCtx);
        return new Resolved(Resolved.Decision.JWT_VERIFIED,
                Optional.of(stamped), Optional.of(jwt));
    }

    private Resolved applyOnUnresolved(ChannelBinding binding, AuthContext baseCtx,
                                       String reason) {
        if (binding.onUnresolved() == OnUnresolvedPolicy.ANONYMOUS) {
            return Resolved.anonymous(baseCtx);
        }
        return Resolved.reject(reason);
    }

    /**
     * Merge: keep the JWT's subject, tenant, issuedAt, and claims; override
     * channel-reserved claim keys with the values minted from the inbound
     * headers (§3 #5). Scopes are unioned with the baseline {@code
     * channel:whatsapp + channel:read} so identity-gated tools still see
     * "this Principal is on the WhatsApp channel."
     */
    private Principal stampChannelClaims(Principal jwtPrincipal, AuthContext baseCtx) {
        Set<String> scopes = new HashSet<>(jwtPrincipal.scopes());
        scopes.addAll(WhatsAppPhoneAuthenticator.BASELINE_SCOPES);

        Map<String, Object> claims = new LinkedHashMap<>(jwtPrincipal.claims());
        claims.putAll(WhatsAppPhoneAuthenticator.channelClaims(baseCtx));

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
                           Optional<String> jwt) {

        public enum Decision { JWT_VERIFIED, ANONYMOUS, REJECT }

        private static Resolved anonymous(AuthContext baseCtx) {
            return new Resolved(Decision.ANONYMOUS, Optional.empty(), Optional.empty());
        }

        private static Resolved reject(String reason) {
            return new Resolved(Decision.REJECT, Optional.empty(), Optional.empty());
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
         */
        public AuthContext authContextForEngine(AuthContext baseCtx) {
            return switch (decision) {
                case JWT_VERIFIED -> new AuthContext(
                        baseCtx.tenant(), baseCtx.headers(),
                        principal.map(Optional::of).orElse(Optional.empty()));
                case ANONYMOUS -> baseCtx;
                case REJECT -> throw new IllegalStateException(
                        "REJECT decision — do not call authContextForEngine; drop the message instead");
            };
        }
    }
}
