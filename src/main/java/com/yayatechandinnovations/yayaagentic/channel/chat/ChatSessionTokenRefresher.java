package com.yayatechandinnovations.yayaagentic.channel.chat;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthIntegrator;
import com.yayatechandinnovations.yayaagentic.channel.auth.AuthResolver;
import com.yayatechandinnovations.yayaagentic.channel.auth.SessionAuthTokenStore;
import com.yayatechandinnovations.yayaagentic.channel.binding.OnUnresolvedPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.SessionTokenRefresher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Re-mints a chat session's forwardable player token via the existing
 * {@code reason=refresh} resolve path. Reconstructs the resolver call from the
 * verified {@link Principal}'s channel claims ({@code channel.kind},
 * {@code channel.entryPoint}, {@code phone}) — stamped by
 * {@link ChatAuthService#resolveForStart}/{@code resolveForExisting} — plus the
 * entry point's auth config, so no extra per-session state has to be threaded
 * down to the tool layer.
 *
 * <p>The {@code REFRESH} dispatch bypasses the token cache, calls the tenant's
 * resolve-identity endpoint with a fresh request id + jti, re-verifies the
 * upgraded JWT ({@code PLAYER_QUERY_OWN} + {@code customerRef}) through the auth
 * chain, updates the cache, and here we re-persist it to the session row. The
 * returned {@code Authorization} header is what the HTTP tool dispatcher swaps
 * in before its single retry.
 *
 * <p>Only chat resolver-exchange sessions can be refreshed today; other
 * channels return empty so the caller surfaces a normal error rather than
 * looping. WhatsApp is a straightforward extension (reconstruct from the sender
 * binding) left for when that channel grows own-scoped tools.
 */
@Component
public class ChatSessionTokenRefresher implements SessionTokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionTokenRefresher.class);

    /** Bound the per-session cache so a long-lived process can't accumulate
     *  tokens for ended sessions without limit (mirrors {@code ChannelAuthTrace}). */
    private static final int MAX_ENTRIES = 10_000;

    private final ChatEntryPointService entryPoints;
    private final ChannelAuthIntegrator channelAuth;
    private final SessionAuthTokenStore tokenStore;

    /** sessionId → latest minted {@code Authorization} header, so a proactive
     *  refresh reaches subsequent same-turn calls. */
    private final Map<String, String> currentBySession = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    public ChatSessionTokenRefresher(ChatEntryPointService entryPoints,
                                     ChannelAuthIntegrator channelAuth,
                                     SessionAuthTokenStore tokenStore) {
        this.entryPoints = entryPoints;
        this.channelAuth = channelAuth;
        this.tokenStore = tokenStore;
    }

    @Override
    public Optional<String> current(ExecutionContext ctx) {
        if (ctx == null || ctx.sessionId() == null) return Optional.empty();
        return Optional.ofNullable(currentBySession.get(ctx.sessionId().value()));
    }

    @Override
    public Optional<String> refresh(ExecutionContext ctx) {
        if (ctx == null || ctx.principal() == null || ctx.sessionId() == null) return Optional.empty();
        Principal p = ctx.principal();
        Map<String, Object> claims = p.claims() == null ? Map.of() : p.claims();

        // Only chat resolver-exchange sessions carry the claims needed to
        // re-drive the resolver; anything else is not refreshable here.
        if (!"chat".equals(str(claims.get("channel.kind")))) return Optional.empty();
        String entryPointId = str(claims.get("channel.entryPoint"));
        String callerPhone = str(claims.get("phone"));
        if (entryPointId == null || callerPhone == null || p.tenant() == null) return Optional.empty();

        ChatEntryPoint ep;
        try {
            ep = entryPoints.requireForTenant(p.tenant(), new ChatEntryPointId(entryPointId));
        } catch (RuntimeException e) {
            log.warn("token refresh: entry point {} not loadable for tenant {} — {}",
                    entryPointId, p.tenant().value(), e.getMessage());
            return Optional.empty();
        }
        if (!(ep.authConfig() instanceof AuthConfig.ResolverExchange rx)) return Optional.empty();

        SenderId sender = new SenderId(ep.id().value());
        OnUnresolvedPolicy onUnresolved = ep.authMode().requiresTokenToStart()
                ? OnUnresolvedPolicy.REJECT : OnUnresolvedPolicy.ANONYMOUS;
        AuthContext baseCtx = new AuthContext(p.tenant(), Map.of(), Optional.empty());

        Map<String, Object> channelClaims = new LinkedHashMap<>();
        channelClaims.put("channel.kind", "chat");
        Object handle = claims.get("channel.handle");
        channelClaims.put("channel.handle", handle != null ? handle : ep.handle());
        channelClaims.put("channel.entryPoint", entryPointId);
        channelClaims.put("phone", callerPhone);

        ChannelAuthIntegrator.Resolved resolved = channelAuth.resolveFor(
                p.tenant(), rx.authResolverId(), onUnresolved, baseCtx, ctx.sessionId(),
                sender, null, rx.senderKey(), callerPhone, "chat",
                ChatAuthService.CHAT_BASELINE_SCOPES, channelClaims,
                AuthResolver.ResolveReason.REFRESH);

        Optional<String> jwt = resolved.jwt();
        if (jwt.isEmpty()) {
            // Resolver miss / verification failure on refresh — the caller must
            // NOT retry, so return empty and let it surface a normal error.
            log.info("token refresh for session {} produced no upgraded token (decision={})",
                    ctx.sessionId().value(), resolved.decision());
            return Optional.empty();
        }
        tokenStore.persist(ctx.sessionId(), jwt.get());
        String header = "Bearer " + jwt.get();
        currentBySession.put(ctx.sessionId().value(), header);
        return Optional.of(header);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString().isBlank() ? null : v.toString();
    }
}
