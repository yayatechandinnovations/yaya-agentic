package com.yayatechandinnovations.yayaagentic.commerce;

import java.time.Duration;

/**
 * Pull cadence / freshness policy for {@code HttpApi} and {@code Database}
 * catalog locations. A live pass-through per query is freshest but slower and
 * has no semantic search; a periodic sync into the {@code products} table is
 * fast + semantic with a staleness window. See {@code ecommerce-chat-design.md}
 * §7.1 and open question #2.
 */
public record CatalogSyncPolicy(
        Mode mode,
        Duration ttl
) {
    public enum Mode {
        /** Call the merchant API on every search — always fresh, no local index. */
        LIVE,
        /** Periodically sync into the local products table — fast + semantic. */
        SYNC
    }

    public static CatalogSyncPolicy live() {
        return new CatalogSyncPolicy(Mode.LIVE, Duration.ZERO);
    }

    public static CatalogSyncPolicy syncEvery(Duration ttl) {
        return new CatalogSyncPolicy(Mode.SYNC, ttl);
    }
}
