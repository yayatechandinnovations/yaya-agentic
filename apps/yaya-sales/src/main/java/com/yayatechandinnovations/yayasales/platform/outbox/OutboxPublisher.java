package com.yayatechandinnovations.yayasales.platform.outbox;

/**
 * A delivery target for outbox events — merchant webhooks and the
 * yaya-agentic system-message bridge implement this (design §13). Delivery is
 * at-least-once: a publisher may see the same event twice and consumers dedupe
 * on {@link OutboxEvent#id()}. Throwing keeps the event PENDING for retry.
 */
public interface OutboxPublisher {

    void publish(OutboxEvent event);

    record OutboxEvent(long id, String storeId, String aggregate, String aggregateId,
                       String eventType, String payloadJson) {}
}
