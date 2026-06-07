package com.yayatechandinnovations.yayaagentic.memory;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.Map;

/**
 * Per-session ephemeral KV. Every operation refreshes the TTL — only
 * truly idle sessions expire. Backed by Redis in production; the SPI is
 * here so tests can swap in a deterministic in-memory implementation.
 */
public interface WorkingMemory {

    /** Replace the entire slot bag for a session. Convenient for fresh starts. */
    void set(Ids.SessionId sessionId, Map<String, Object> values);

    /** Merge new values into the existing bag (later wins per key). */
    void merge(Ids.SessionId sessionId, Map<String, Object> values);

    /** Return the full slot bag. Empty map when the session has none / expired. */
    Map<String, Object> get(Ids.SessionId sessionId);

    /** Drop the entire bag. */
    void clear(Ids.SessionId sessionId);

    /** Drop specific keys. No-op when none of them exist. */
    void remove(Ids.SessionId sessionId, String... keys);
}
