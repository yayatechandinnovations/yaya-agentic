package com.yayatechandinnovations.yayaagentic.profile;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;

import java.util.List;

/**
 * The user-meaningful unit. Surfaced as a quick-reply chip and as LLM
 * guidance for tool selection. Backed by one or more tools. See design §5.3.
 */
public record Capability(
        Ids.CapabilityId id,
        String userFacingLabel,
        String userFacingDescription,
        String llmGuidance,
        List<Ids.ToolId> tools,
        PermissionRequirement requires
) {}
