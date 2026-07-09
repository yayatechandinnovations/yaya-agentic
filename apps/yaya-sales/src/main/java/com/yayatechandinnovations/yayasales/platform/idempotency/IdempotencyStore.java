package com.yayatechandinnovations.yayasales.platform.idempotency;

import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;

import java.util.Optional;

/**
 * Idempotency store (design §5.5, §17): every state-changing call carries an
 * {@code Idempotency-Key}; a repeat of the same {@code (store, key)} returns
 * the stored response without re-executing — checkout, capture, and stock
 * moves stay safe under network retries, at-least-once webhooks, double-taps.
 *
 * <p>The {@code requestHash} detects the dangerous variant: the same key
 * replayed with a <em>different</em> request body. That is a caller bug, and
 * the endpoint must answer 409/422 rather than return a response computed for
 * different inputs.
 */
public interface IdempotencyStore {

    Optional<Stored> find(StoreId store, String key);

    void put(StoreId store, String key, String requestHash, String responseJson);

    record Stored(String requestHash, String responseJson) {

        public boolean matches(String hash) {
            return requestHash.equals(hash);
        }
    }
}
