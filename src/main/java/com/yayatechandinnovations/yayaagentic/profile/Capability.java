package com.yayatechandinnovations.yayaagentic.profile;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;

import java.util.List;

/**
 * The user-meaningful unit. Surfaced as a quick-reply chip and as LLM
 * guidance for tool selection. Backed by one or more tools.
 *
 * <p>{@code followUpHints} are emitted by the engine as
 * {@code UiHint("quick_replies", …)} events AFTER a successful tool
 * dispatch — "anything else?" style continuations the UI renders as chips.
 * Empty list = no follow-ups. See design §5.3, §6.1.</p>
 */
public record Capability(
        Ids.CapabilityId id,
        String userFacingLabel,
        String userFacingDescription,
        String llmGuidance,
        List<Ids.ToolId> tools,
        List<String> followUpHints,
        PermissionRequirement requires
) {}
