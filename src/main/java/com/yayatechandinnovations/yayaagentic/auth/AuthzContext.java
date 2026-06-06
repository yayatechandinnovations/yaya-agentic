package com.yayatechandinnovations.yayaagentic.auth;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.Map;

public record AuthzContext(
        Ids.SessionId sessionId,
        Ids.TurnId turnId,
        String traceId,
        Map<String, Object> attributes
) {}
