package com.yayatechandinnovations.yayaagentic.channel.chat;

import com.yayatechandinnovations.yayaagentic.api.ChatApiException;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.anon.AnonymousAuthenticator;
import com.yayatechandinnovations.yayaagentic.auth.anon.VerifiedDeviceAuthenticator;
import com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthIntegrator;
import com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthTrace;
import com.yayatechandinnovations.yayaagentic.channel.auth.cache.AuthResolverTokenCache;
import com.yayatechandinnovations.yayaagentic.channel.binding.OnUnresolvedPolicy;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.VerifiedDevice;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The single, transport-independent home for chat identity resolution and its
 * playground/production auth-flow trace. Both the public front door
 * ({@code PublicChatController}) and the operator chat playground
 * ({@code PlaygroundChatController}) drive their session auth through this
 * service so the two surfaces can never drift on the security-critical policy
 * ({@code chat-channel-design.md} §9): resolver-exchange first, then bearer
 * (L2), then phone-verified device (L1), then anonymous.
 *
 * <p>The public door reads the caller identity from HTTP headers (the verified
 * {@code X-Device-Pass}, the {@code Authorization} bearer); the playground
 * synthesises it from operator-chosen inputs. Both hand this service a {@link
 * Caller} — an identity decoupled from the {@code ServerWebExchange} — so the
 * downstream decision tree is literally the same code.
 *
 * <p>Every resolution also records a {@link ChannelAuthTrace} entry keyed by the
 * engine session id, which is what makes a chat session inspectable via
 * {@code /v1/sessions/{id}/inspector}: without the trace's tenant + caller hash
 * the inspector can't locate the recorded resolver exchanges. A {@code REJECT}
 * is returned as data (not thrown) so the playground can present the failing
 * exchange for debugging while the public door still fails closed.
 */
@Service
public class ChatAuthService {

    /** Baseline scopes stamped onto a resolver-verified chat Principal so
     *  identity-gated tools see "this Principal is on the chat channel." Public
     *  so a token refresh ({@code SessionTokenRefresher}) re-mints with the same
     *  channel scopes it was first minted with. */
    public static final Set<String> CHAT_BASELINE_SCOPES = Set.of("channel:chat", "channel:read");

    private final ChannelAuthIntegrator channelAuth;
    private final VerifiedDeviceAuthenticator verifiedDeviceAuth;
    private final AnonymousAuthenticator anon;
    private final ChannelAuthTrace authTrace;

    public ChatAuthService(ChannelAuthIntegrator channelAuth,
                           VerifiedDeviceAuthenticator verifiedDeviceAuth,
                           AnonymousAuthenticator anon,
                           ChannelAuthTrace authTrace) {
        this.channelAuth = channelAuth;
        this.verifiedDeviceAuth = verifiedDeviceAuth;
        this.anon = anon;
        this.authTrace = authTrace;
    }

    /**
     * A resolved caller identity, decoupled from the HTTP exchange. The public
     * door builds it from request headers; the playground synthesises it from
     * operator inputs. {@code verificationHeaders} is the header map used to
     * build the L2 (tenant-bearer) {@link AuthContext} — the public door passes
     * the full request headers (so the auth chain sees exactly what it does
     * today); the playground passes just a synthesised Authorization header.
     */
    public record Caller(Optional<VerifiedDevice> device,
                         Optional<String> bearer,
                         Map<String, String> verificationHeaders) {

        public static Caller anonymous() {
            return new Caller(Optional.empty(), Optional.empty(), Map.of());
        }

        public static Caller device(VerifiedDevice device) {
            return new Caller(Optional.of(device), Optional.empty(), Map.of());
        }

        public static Caller bearer(String token) {
            return new Caller(Optional.empty(), Optional.of(token),
                    Map.of("Authorization", "Bearer " + token));
        }

        public boolean hasBearer() {
            return bearer.isPresent() && !bearer.get().isBlank();
        }
    }

    public enum Decision { RESOLVED_JWT, DEVICE, BEARER, ANONYMOUS, REJECT }

    /**
     * The AuthContext to run the session under (null on {@code REJECT}), the
     * resolver-minted JWT to persist (present only for {@code RESOLVED_JWT}), and
     * the facts the auth-flow inspector needs. {@code callerSha256} matches the
     * key {@code HttpAuthResolver} records exchanges under, so the inspector can
     * find them.
     */
    public record Resolution(Decision decision,
                             AuthContext auth,
                             Optional<String> jwt,
                             boolean startedAuthenticated,
                             Long resolverId,
                             String onUnresolved,
                             String verificationDecision,
                             String verificationDetail,
                             String callerSha256) {

        public boolean rejected() { return decision == Decision.REJECT; }
    }

    /* ---------------------------- start ----------------------------------- */

    /**
     * The session-start decision tree (mirrors the public door's original
     * {@code authForStart}). Throws {@link ChatApiException} for request-shape
     * problems (a resolver-exchange chat with no verified device, an
     * AUTH_REQUIRED chat with no bearer). {@code REJECT} — an AUTH_REQUIRED
     * resolver that returned no account — is returned as data.
     */
    public Resolution resolveForStart(ChatEntryPoint ep, String handle, String deviceId, Caller caller) {
        // Server-side transparent sign-in wins first: a resolver-exchange entry
        // point never falls back to "paste a token."
        if (ep.authConfig() instanceof AuthConfig.ResolverExchange rx) {
            VerifiedDevice device = caller.device().orElseThrow(ChatApiException::verificationRequired);
            return resolverExchange(ep, handle, rx.authResolverId(), rx.senderKey(), device);
        }

        if (ep.requiresVerifiedPhone() && caller.device().isEmpty()) {
            throw ChatApiException.verificationRequired();
        }

        // AUTH_REQUIRED → a tenant account (L2) is mandatory.
        if (ep.authMode().requiresTokenToStart()) {
            if (!caller.hasBearer()) throw ChatApiException.authRequired();
            return bearer(bearerContext(ep, caller));
        }
        // AUTH_OPTIONAL with a tenant bearer → L2 wins.
        if (caller.hasBearer() && ep.authMode() == AuthMode.AUTH_OPTIONAL) {
            return bearer(bearerContext(ep, caller));
        }
        // L1: a phone-verified device beats anonymous.
        if (caller.device().isPresent()) {
            VerifiedDevice device = caller.device().get();
            return device(verifiedDeviceContext(ep, handle, device), callerHash(device.phoneE164()));
        }
        return anonymous(anonymousContext(ep, handle, deviceId));
    }

    /* --------------------------- existing --------------------------------- */

    /**
     * The per-message re-resolution (mirrors the public door's {@code
     * authForExisting}). {@code sessionAuthenticated} is the stored "this session
     * was started/upgraded authenticated" bit; the public door reads it from the
     * session registry, the playground from its own session state.
     */
    public Resolution resolveForExisting(ChatEntryPoint ep, String handle, String deviceId,
                                         boolean sessionAuthenticated, Caller caller) {
        if (ep.authConfig() instanceof AuthConfig.ResolverExchange rx) {
            VerifiedDevice device = caller.device().orElseThrow(ChatApiException::verificationRequired);
            return resolverExchange(ep, handle, rx.authResolverId(), rx.senderKey(), device);
        }
        if (!isAnonymous(ep.authMode(), sessionAuthenticated, caller)) {
            return bearer(bearerContext(ep, caller)); // L2 tenant account
        }
        if (ep.requiresVerifiedPhone() && caller.device().isEmpty()) {
            throw ChatApiException.verificationRequired();
        }
        if (caller.device().isPresent()) {
            VerifiedDevice device = caller.device().get();
            return device(verifiedDeviceContext(ep, handle, device), callerHash(device.phoneE164()));
        }
        return anonymous(anonymousContext(ep, handle, deviceId));
    }

    /** Anonymous unless the mode forbids it, the session was upgraded, or a
     *  bearer is presented (mirrors the public door's {@code isAnonymous}). */
    private boolean isAnonymous(AuthMode mode, boolean sessionAuthenticated, Caller caller) {
        if (sessionAuthenticated) return false;
        if (mode == AuthMode.ANONYMOUS_ONLY) return true;
        if (mode == AuthMode.AUTH_REQUIRED) return false;
        return !caller.hasBearer(); // AUTH_OPTIONAL: anon until a bearer appears
    }

    /* --------------------------- resolver --------------------------------- */

    /** Drive the shared {@link ChannelAuthIntegrator} from the verified device
     *  phone. Never throws on a resolver miss — {@code REJECT} is returned so the
     *  caller decides whether to fail closed (public) or render the failing
     *  exchange (playground). */
    private Resolution resolverExchange(ChatEntryPoint ep, String handle, long resolverId,
                                        String senderKey, VerifiedDevice device) {
        OnUnresolvedPolicy onUnresolved = ep.authMode().requiresTokenToStart()
                ? OnUnresolvedPolicy.REJECT : OnUnresolvedPolicy.ANONYMOUS;
        AuthContext baseCtx = new AuthContext(ep.tenant(), Map.of(), Optional.empty());
        SenderId sender = new SenderId(ep.id().value());
        String callerPhone = device.phoneE164();
        String callerSha256 = callerHash(callerPhone);

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("channel.kind", "chat");
        claims.put("channel.handle", handle);
        claims.put("channel.entryPoint", ep.id().value());
        if (callerPhone != null) claims.put("phone", callerPhone);

        // Chat has no sender phone (7th arg null); it authorizes with the entry
        // point's tenant-issued senderKey instead.
        ChannelAuthIntegrator.Resolved resolved = channelAuth.resolveFor(
                ep.tenant(), resolverId, onUnresolved, baseCtx,
                null, sender, null, senderKey, callerPhone, "chat",
                CHAT_BASELINE_SCOPES, claims);

        String on = onUnresolved.name();
        return switch (resolved.decision()) {
            case JWT_VERIFIED -> new Resolution(Decision.RESOLVED_JWT,
                    resolved.authContextForEngine(baseCtx), resolved.jwt(), true,
                    resolverId, on, "JWT_VERIFIED",
                    "resolver returned a JWT the trust chain accepted", callerSha256);
            case ANONYMOUS -> new Resolution(Decision.DEVICE,
                    verifiedDeviceContext(ep, handle, device), Optional.empty(), false,
                    resolverId, on, "ANONYMOUS",
                    "resolver returned no account; fell back to the phone-verified device", callerSha256);
            case REJECT -> new Resolution(Decision.REJECT,
                    null, Optional.empty(), false,
                    resolverId, on, "REJECT",
                    "resolver returned no account and on_unresolved=REJECT", callerSha256);
        };
    }

    /* ------------------------- context mints ------------------------------ */

    public AuthContext verifiedDeviceContext(ChatEntryPoint ep, String handle, VerifiedDevice device) {
        Principal p = verifiedDeviceAuth.mint(ep.tenant(), device, handle);
        return new AuthContext(ep.tenant(), Map.of(), Optional.of(p));
    }

    public AuthContext anonymousContext(ChatEntryPoint ep, String handle, String deviceId) {
        Principal p = anon.mintAnonymous(ep.tenant(), handle, deviceId);
        return new AuthContext(ep.tenant(), Map.of(), Optional.of(p));
    }

    /** Let the authenticator chain verify the bearer (OIDC / shared-secret)
     *  against the entry point's tenant trust — no chat-specific verification. */
    private AuthContext bearerContext(ChatEntryPoint ep, Caller caller) {
        return new AuthContext(ep.tenant(), Map.copyOf(caller.verificationHeaders()), Optional.empty());
    }

    /* -------------------------- trace record ------------------------------ */

    /**
     * Record the identity-resolution stages for the inspector, keyed by the
     * engine session id. Safe to call again on each message — it refreshes the
     * verification decision (which can change if the resolver flips outcome).
     */
    public void recordTrace(Ids.SessionId session, ChatEntryPoint ep, Resolution res,
                            ChannelAuthTrace.TokenState tokenState) {
        if (session == null || res == null) return;
        authTrace.recordBinding(session, ep.id().value(), ep.profile().value(),
                res.resolverId(), res.onUnresolved(),
                ep.tenant().value(), res.callerSha256());
        authTrace.recordVerification(session, res.verificationDecision(), res.verificationDetail());
        if (tokenState != null) authTrace.recordSessionToken(session, tokenState);
    }

    public void wipeTrace(Ids.SessionId session) {
        authTrace.wipe(session);
    }

    public String callerHash(String phone) {
        return phone == null ? null : AuthResolverTokenCache.sha256LowerHex(phone);
    }

    /* --------------------------- factories -------------------------------- */

    private static Resolution bearer(AuthContext auth) {
        return new Resolution(Decision.BEARER, auth, Optional.empty(), true,
                null, null, "BEARER",
                "tenant bearer verified by the auth chain at session start", null);
    }

    private static Resolution device(AuthContext auth, String callerSha256) {
        return new Resolution(Decision.DEVICE, auth, Optional.empty(), false,
                null, null, "DEVICE_VERIFIED",
                "phone-verified device (L1); no tenant JWT", callerSha256);
    }

    private static Resolution anonymous(AuthContext auth) {
        return new Resolution(Decision.ANONYMOUS, auth, Optional.empty(), false,
                null, null, "ANONYMOUS", "anonymous session", null);
    }
}
