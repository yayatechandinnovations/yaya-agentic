package com.yayatechandinnovations.yayaagentic.recorder;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.Instant;

public record SessionSummary(
        Ids.SessionId id,
        Ids.TenantId tenant,
        Ids.ProfileId profile,
        String principalSubject,
        String channel,
        int turnCount,
        Instant createdAt,
        Instant endedAt
) {}
