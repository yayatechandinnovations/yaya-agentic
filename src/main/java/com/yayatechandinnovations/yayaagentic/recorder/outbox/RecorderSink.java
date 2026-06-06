package com.yayatechandinnovations.yayaagentic.recorder.outbox;

/**
 * A downstream destination for fan-out conversation events. M1 ships none
 * (the outbox is wired but iterates an empty sink list); M5 adds the S3
 * cold sink and the warehouse stream sink behind this SPI.
 */
public interface RecorderSink {

    /** Stable identifier used as the outbox row's {@code sink_id}. */
    String id();

    /** Send (or replay) one outbox payload. Implementations should be idempotent
     *  keyed on the outbox-row's logical key — the dispatcher passes only
     *  successful ack semantics back via {@link #publish}. */
    void publish(OutboxEvent event) throws Exception;

    /** What kinds of events this sink wants. M1 only emits TURN_RECORDED;
     *  M5 sinks may opt in to SESSION_STARTED / SESSION_ENDED as well. */
    boolean accepts(OutboxEvent.Kind kind);
}
