package com.yayatechandinnovations.yayaagentic.profile;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.Map;
import java.util.Optional;

public record StartConversationRequest(
        Ids.TenantId tenant,
        Optional<Ids.ProfileId> explicitProfile,
        String channel,
        Map<String, Object> hints
) {}
