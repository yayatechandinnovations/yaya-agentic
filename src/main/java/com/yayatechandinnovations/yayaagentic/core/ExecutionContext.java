package com.yayatechandinnovations.yayaagentic.core;

import java.util.Map;

/**
 * Per-call context handed to tool handlers and retrievers. Provides identity,
 * session metadata, and a redacted attribute bag for tracing.
 */
public record ExecutionContext(
        Principal principal,
        Ids.SessionId sessionId,
        Ids.TurnId turnId,
        String traceId,
        Map<String, Object> attributes
) {}
