package com.yayatechandinnovations.yayaagentic.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The conversational unit. History lives in the {@code ConversationRecorder};
 * working memory is the per-session KV the engine maintains while the
 * conversation is active. See design §5.7.
 */
public record Session(
        Ids.SessionId id,
        Ids.TenantId tenant,
        Principal principal,
        Ids.ProfileId profile,
        String channel,
        List<Turn> history,
        IntentFrame activeIntent,
        Map<String, Object> workingMemory,
        Instant createdAt,
        Instant endedAt
) {}
