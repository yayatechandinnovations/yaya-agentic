package com.yayatechandinnovations.yayaagentic.channel.auth.cache;

import com.yayatechandinnovations.yayaagentic.channel.auth.AuthResolver;
import com.yayatechandinnovations.yayaagentic.channel.auth.AuthResolverException;
import com.yayatechandinnovations.yayaagentic.channel.auth.AuthResolverRegistry;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.AuthResolverCallEntity;
import com.yayatechandinnovations.yayaagentic.persistence.AuthResolverCallRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelAuthResolverEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelAuthResolverRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates the cache → resolver flow at session start and on refresh.
 * The engine wiring (PR 6) calls {@link #dispatch} once per session start
 * and once per reactive 401; everything else (cache lookup, resolver
 * dispatch, audit write) happens inside.
 *
 * <p>Three outcomes, mirroring {@link Result}:
 * <ul>
 *   <li>{@link Result.TokenIssued} — caller proceeds with the JWT.</li>
 *   <li>{@link Result.Unresolved} — the resolver returned 404. The binding's
 *       {@code on_unresolved} decides whether the inbound proceeds as
 *       anonymous or is dropped — that decision is the caller's, not
 *       this dispatcher's.</li>
 *   <li>{@link Result.Failed} — transport / config error. The
 *       resolver's {@code on_refresh_failure} field governs the caller's
 *       next move.</li>
 * </ul>
 *
 * <p>See {@code docs/design/channel-auth-resolver-design.md} §9.
 */
@Component
public class AuthResolverDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AuthResolverDispatcher.class);

    private final AuthResolverRegistry registry;
    private final ChannelAuthResolverRepository resolverRepo;
    private final AuthResolverTokenCache cache;
    private final AuthResolverCallRepository auditRepo;
    private final Clock clock;

    public AuthResolverDispatcher(AuthResolverRegistry registry,
                                  ChannelAuthResolverRepository resolverRepo,
                                  AuthResolverTokenCache cache,
                                  AuthResolverCallRepository auditRepo,
                                  Clock clock) {
        this.registry = registry;
        this.resolverRepo = resolverRepo;
        this.cache = cache;
        this.auditRepo = auditRepo;
        this.clock = clock;
    }

    /**
     * Resolve identity for a session start or refresh.
     *
     * @param resolverId  binding's {@code auth_resolver_id} — must be ACTIVE
     * @param tenant      tenant the resolver belongs to
     * @param sender      channel endpoint receiving the inbound
     * @param senderPhone E.164 phone the channel endpoint owns
     * @param callerPhone E.164 phone of the inbound message author
     * @param session     yaya session this resolve is for
     * @param channel     short channel kind id, e.g. {@code "whatsapp"}
     * @param reason      {@link AuthResolver.ResolveReason#INITIAL} or
     *                    {@link AuthResolver.ResolveReason#REFRESH}
     */
    public Result dispatch(long resolverId,
                           Ids.TenantId tenant,
                           SenderId sender,
                           String senderPhone,
                           String senderKey,
                           String callerPhone,
                           Ids.SessionId session,
                           String channel,
                           AuthResolver.ResolveReason reason) {
        Optional<ChannelAuthResolverEntity> rowOpt = resolverRepo.findById(resolverId);
        if (rowOpt.isEmpty() || !"ACTIVE".equals(rowOpt.get().getStatus())) {
            audit(tenant, "?", session, sender, callerPhone, channel, reason,
                    null, "config_error", "MISS", null,
                    "resolver " + resolverId + " not active");
            return new Result.Failed("config_error",
                    "auth resolver " + resolverId + " is not active");
        }
        ChannelAuthResolverEntity row = rowOpt.get();
        AuthResolverTokenCache.CacheKey cacheKey =
                new AuthResolverTokenCache.CacheKey(tenant, sender, callerPhone);

        // §9.3 — cache check on INITIAL only; REFRESH always re-dispatches.
        if (reason == AuthResolver.ResolveReason.INITIAL && row.isCacheEnabled()) {
            Optional<AuthResolverTokenCache.CachedIdentity> cached = cache.getIdentity(cacheKey);
            if (cached.isPresent()) {
                audit(tenant, row.getName(), session, sender, callerPhone, channel,
                        reason, 0, "ok", "HIT", null, null);
                return new Result.TokenIssued(cached.get().jwt(), cached.get().profile(),
                        CacheState.HIT);
            }
        }

        AuthResolver resolver;
        try {
            resolver = registry.materialize(row);
        } catch (AuthResolverException are) {
            audit(tenant, row.getName(), session, sender, callerPhone, channel,
                    reason, null, are.status(), "MISS", are.httpStatus(), are.getMessage());
            return new Result.Failed(are.status(), are.getMessage());
        }

        long startedAtMillis = Instant.now(clock).toEpochMilli();
        Optional<AuthResolver.ResolvedIdentity> resolved;
        try {
            resolved = resolver.resolveIdentity(new AuthResolver.ResolveRequest(
                    tenant, sender, senderPhone, senderKey, callerPhone, session, channel,
                    reason, Instant.now(clock)));
        } catch (AuthResolverException are) {
            int elapsed = (int) (Instant.now(clock).toEpochMilli() - startedAtMillis);
            audit(tenant, row.getName(), session, sender, callerPhone, channel,
                    reason, elapsed, are.status(),
                    reason == AuthResolver.ResolveReason.REFRESH ? "REFRESH" : "MISS",
                    are.httpStatus(), are.getMessage());
            // On reactive refresh, drop the now-stale cache entry — the
            // engine will re-decide based on on_refresh_failure.
            if (reason == AuthResolver.ResolveReason.REFRESH) cache.evict(cacheKey);
            return new Result.Failed(are.status(), are.getMessage());
        } catch (RuntimeException rte) {
            int elapsed = (int) (Instant.now(clock).toEpochMilli() - startedAtMillis);
            audit(tenant, row.getName(), session, sender, callerPhone, channel,
                    reason, elapsed, "transport_error",
                    reason == AuthResolver.ResolveReason.REFRESH ? "REFRESH" : "MISS",
                    null, rte.getClass().getSimpleName());
            if (reason == AuthResolver.ResolveReason.REFRESH) cache.evict(cacheKey);
            return new Result.Failed("transport_error",
                    "resolver '" + row.getName() + "' failed unexpectedly");
        }
        int elapsed = (int) (Instant.now(clock).toEpochMilli() - startedAtMillis);

        if (resolved.isEmpty()) {
            audit(tenant, row.getName(), session, sender, callerPhone, channel,
                    reason, elapsed, "not_found",
                    reason == AuthResolver.ResolveReason.REFRESH ? "REFRESH" : "MISS",
                    null, null);
            // 404 on refresh wipes the cached token — the user is gone.
            if (reason == AuthResolver.ResolveReason.REFRESH) cache.evict(cacheKey);
            return new Result.Unresolved();
        }

        String jwt = resolved.get().jwt();
        Optional<AuthResolver.ProfileSelection> profile = resolved.get().profile();
        // Success path: persist to cache + audit.
        cache.put(cacheKey, jwt, profile,
                row.getRefreshWindowSeconds(),
                row.getMaxCacheSeconds());
        audit(tenant, row.getName(), session, sender, callerPhone, channel,
                reason, elapsed, "ok",
                reason == AuthResolver.ResolveReason.REFRESH ? "REFRESH" : "MISS",
                null, null);
        return new Result.TokenIssued(jwt, profile,
                reason == AuthResolver.ResolveReason.REFRESH
                        ? CacheState.REFRESH : CacheState.MISS);
    }

    /** Single-key eviction for the reactive 401 path AND for admin
     *  invalidate-cache calls (§8 eviction paths 2 + 4). */
    public void evict(Ids.TenantId tenant, SenderId sender, String callerPhone) {
        cache.evict(new AuthResolverTokenCache.CacheKey(tenant, sender, callerPhone));
    }

    /** Pattern-scoped eviction for resolver row deletion (§8 eviction path 5).
     *  Returns the number of keys removed for the admin UI to surface. */
    public long evictAllForTenant(Ids.TenantId tenant) {
        return cache.evictAllForTenant(tenant);
    }

    private void audit(Ids.TenantId tenant, String resolverName, Ids.SessionId session,
                       SenderId sender, String callerPhone, String channel,
                       AuthResolver.ResolveReason reason, Integer elapsedMs,
                       String status, String cacheState, Integer httpStatus,
                       String errorSummary) {
        try {
            String callerHash = AuthResolverTokenCache.sha256LowerHex(callerPhone);
            UUID sessionUuid = session == null ? null : parseUuid(session.value());
            AuthResolverCallEntity row = new AuthResolverCallEntity(
                    tenant.value(),
                    resolverName,
                    sessionUuid,
                    sender.value(),
                    callerHash,
                    channel,
                    reason.name(),
                    OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
                    status,
                    cacheState);
            row.setElapsedMs(elapsedMs);
            row.setHttpStatus(httpStatus);
            row.setErrorSummary(truncate(errorSummary, 512));
            auditRepo.save(row);
        } catch (RuntimeException e) {
            // Audit MUST never block the auth flow. Log loud, move on.
            log.warn("auth_resolver_calls audit write failed: {} ({})",
                    e.getMessage(), e.getClass().getSimpleName());
        }
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public enum CacheState { HIT, MISS, REFRESH }

    /** Sealed three-way outcome the caller switches on. */
    public sealed interface Result permits Result.TokenIssued, Result.Unresolved, Result.Failed {
        /** @param profile the resolver's optional profile selection, carried to
         *                 the integrator so it can override the bound profile. */
        record TokenIssued(String jwt, Optional<AuthResolver.ProfileSelection> profile,
                           CacheState cacheState) implements Result {
            public TokenIssued {
                profile = profile == null ? Optional.empty() : profile;
            }
        }
        record Unresolved() implements Result {}
        record Failed(String status, String message) implements Result {}
    }
}
