package com.yayatechandinnovations.yayaidentity.platform.outbox;

/**
 * Transactional outbox (design §14). Identity events — {@code user.created},
 * {@code membership.added}, {@code role.assigned}, {@code policy.changed},
 * {@code client.secret_rotated} — are appended in the SAME transaction as the
 * state change, so a consumer can never observe a change without its event. A
 * relay publishes {@code PENDING} rows to subscribers (I2+).
 */
public interface OutboxRelay {

    void append(String aggregate, String aggregateId, String eventType, String payloadJson);
}
