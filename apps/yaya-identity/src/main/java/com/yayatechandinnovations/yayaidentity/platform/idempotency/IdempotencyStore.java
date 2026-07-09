package com.yayatechandinnovations.yayaidentity.platform.idempotency;

import java.util.Optional;

/**
 * Idempotency store (design §14, PRD FR-7.3). Mutating admin/token calls carry
 * an {@code Idempotency-Key}; a repeat of the same {@code (scope, key)} returns
 * the first stored response instead of re-executing the operation.
 */
public interface IdempotencyStore {

    Optional<String> find(String scope, String key);

    void put(String scope, String key, String responseJson);
}
