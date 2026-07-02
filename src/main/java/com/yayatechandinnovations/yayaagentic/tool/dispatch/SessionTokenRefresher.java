package com.yayatechandinnovations.yayaagentic.tool.dispatch;

import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;

import java.util.Optional;

/**
 * Re-mints the forwardable principal token for a session on demand — the
 * agent-side half of the Smart Lottery player-scope upgrade contract
 * ({@code docs/design/session-start-flows.md} background; resolver design §9).
 *
 * <p>A guest player who registers mid-session is still holding the
 * pre-registration token: {@code PLAYER_QUERY_OWN} calls fail until the token
 * is re-minted with the upgraded scope + {@code customerRef}. Only the agent
 * can re-mint (it holds the session token), so the HTTP tool layer calls this
 * when an own-scoped endpoint self-heals with a {@code 409 token_refresh_required}
 * (reactive, {@link #refresh}) or when a tool result flags
 * {@code tokenRefreshRequired} (proactive fast-path).
 *
 * <p>Implementations re-drive the existing {@code reason=refresh} resolve path
 * (fresh request id + jti), re-persist the token to per-conversation storage,
 * and return the new {@code Authorization} header value so the very next call
 * in the turn carries the upgraded scope.
 */
public interface SessionTokenRefresher {

    /**
     * The latest forwardable {@code Authorization} header value already minted
     * for this session by a prior {@link #refresh} in the same conversation, if
     * any. Lets a proactively-refreshed token reach subsequent same-turn tool
     * calls whose {@code inboundAuthorization} was fixed before the refresh.
     * Empty when no refresh has happened — the caller falls back to the inbound
     * token.
     */
    Optional<String> current(ExecutionContext ctx);

    /**
     * Force a {@code reason=refresh} re-mint for the session's caller and
     * persist it. Returns the new {@code Authorization} header value
     * (e.g. {@code "Bearer …"}), or empty when the session cannot be refreshed
     * (unknown channel, no resolver, resolver miss) — in which case the caller
     * must surface a normal error rather than retry.
     */
    Optional<String> refresh(ExecutionContext ctx);
}
