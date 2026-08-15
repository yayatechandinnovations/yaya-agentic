package com.yayatechandinnovations.yayaagentic.channel.network.tools;

import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient;
import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient.BrokerError;
import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient.BrokerResponse;
import com.yayatechandinnovations.yayaagentic.channel.network.NetworkToolSupport;
import com.yayatechandinnovations.yayaagentic.channel.network.PlayCredential;
import com.yayatechandinnovations.yayaagentic.channel.network.PlayCredentialCache;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.engine.banca.BancaContext;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import org.springframework.http.HttpMethod;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Shared machinery for the S3 play-path tools ({@code play_quote},
 * {@code play_confirm}, {@code prize_claim_network}) — design §2/§4/§6:
 *
 * <ul>
 *   <li>lazy {@link PlayCredential} acquisition, cached per (session, install),
 *       re-brokered on expiry/401;</li>
 *   <li>a single transparent token-refresh retry around install play-surface
 *       calls (§4.1 "token expiry mid-flow → transparent refresh");</li>
 *   <li>the customer token NEVER appears in a returned value (§6 redaction).</li>
 * </ul>
 */
abstract class AbstractPlayTool implements ToolHandler<Map<String, Object>, Map<String, Object>> {

    protected final HubBrokerClient hub;
    protected final NetworkToolSupport support;
    protected final WorkingMemory workingMemory;
    protected final PlayCredentialCache credentials;

    protected AbstractPlayTool(HubBrokerClient hub, NetworkToolSupport support,
                               WorkingMemory workingMemory, PlayCredentialCache credentials) {
        this.hub = hub;
        this.support = support;
        this.workingMemory = workingMemory;
        this.credentials = credentials;
    }

    /** The committed banca context (installCode etc.), or empty when none. */
    protected Optional<BancaContext> banca(ExecutionContext ctx) {
        return BancaContext.read(workingMemory, ctx.sessionId());
    }

    /**
     * A live brokered credential for this session+banca — from cache, else
     * brokered via the hub {@code play-session} op. Never logged.
     */
    protected Optional<PlayCredential> credentialFor(ExecutionContext ctx, String installCode, String phone) {
        Instant now = Instant.now();
        Optional<PlayCredential> cached = credentials.get(ctx.sessionId(), installCode, now);
        if (cached.isPresent()) return cached;
        BrokerResponse resp = hub.playSession("play", installCode, phone, support.sessionRef(ctx));
        if (!resp.ok()) return Optional.empty();
        Optional<PlayCredential> fresh = hub.parseCredential(resp.body());
        fresh.ifPresent(c -> credentials.put(ctx.sessionId(), installCode, c));
        return fresh;
    }

    /**
     * Invoke an install play-surface call with the brokered credential, retrying
     * once on {@code TOKEN_EXPIRED} after re-brokering. Returns the broker error
     * when it still fails.
     */
    protected BrokerResponse callWithRefresh(ExecutionContext ctx, String installCode, String phone,
                                             Function<PlayCredential, BrokerResponse> call) {
        PlayCredential cred = credentialFor(ctx, installCode, phone).orElse(null);
        if (cred == null) return BrokerResponse.error(BrokerError.PLAY_DISABLED);
        BrokerResponse resp = call.apply(cred);
        if (resp.ok() || resp.error() != BrokerError.TOKEN_EXPIRED) return resp;
        // Transparent refresh (§4.1): drop the stale token and re-broker once.
        credentials.invalidate(ctx.sessionId(), installCode);
        PlayCredential refreshed = credentialFor(ctx, installCode, phone).orElse(null);
        if (refreshed == null) return BrokerResponse.error(BrokerError.PLAY_DISABLED);
        return call.apply(refreshed);
    }

    protected BrokerResponse playSurface(PlayCredential cred, HttpMethod method, String path,
                                         Map<String, Object> body, ExecutionContext ctx) {
        return hub.playSurface(cred, method, path, body, support.sessionRef(ctx));
    }
}
