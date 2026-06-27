package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

import com.yayatechandinnovations.yayaagentic.persistence.ApiKeyEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ApiKeyRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifier cache keyed on the 11-char prefix (NOT the secret). Holds the active
 * rows for a prefix for a short TTL so the steady-state hot path is a map hit,
 * not a DB probe. See {@code docs/design/api-keys-design.md} §5.2.
 *
 * <p>v1 storage is in-process (single-node correct): {@link #evict(String)} is
 * called synchronously on revoke so a revoked key stops working immediately on
 * this node. The §4.4 Redis pub/sub broadcast that fans eviction across a
 * multi-node cluster is a documented follow-up; until then a revoked key can
 * survive at most {@code cache-ttl-seconds} on a *peer* node.</p>
 *
 * <p>The cache stores the {@code secret_hash}, never the raw secret — a
 * compromised cache cannot replay traffic without already having the bearer.</p>
 */
@Component
public class ApiKeyCache {

    private record Entry(List<ApiKeyEntity> rows, long expiresAtMillis) {}

    private final ApiKeyRepository repo;
    private final long ttlMillis;
    private final ConcurrentHashMap<String, Entry> byPrefix = new ConcurrentHashMap<>();

    public ApiKeyCache(ApiKeyRepository repo, ApiKeysProperties props) {
        this.repo = repo;
        this.ttlMillis = Duration.ofSeconds(Math.max(0, props.cacheTtlSecondsOrDefault())).toMillis();
    }

    /** Active rows for the prefix, served from cache when warm. */
    public List<ApiKeyEntity> activeByPrefix(String prefix) {
        long now = System.currentTimeMillis();
        Entry cached = byPrefix.get(prefix);
        if (cached != null && cached.expiresAtMillis() > now) {
            return cached.rows();
        }
        List<ApiKeyEntity> rows = repo.findByKeyPrefixAndRevokedAtIsNull(prefix);
        // TTL 0 disables caching (per-request DB hit for strict revocation).
        if (ttlMillis > 0) {
            byPrefix.put(prefix, new Entry(rows, now + ttlMillis));
        }
        return rows;
    }

    /** Drop the cached rows for a prefix — called on revoke/rename. */
    public void evict(String prefix) {
        if (prefix != null) byPrefix.remove(prefix);
    }

    /** Test hook. */
    public void clear() {
        byPrefix.clear();
    }
}
