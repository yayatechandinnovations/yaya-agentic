package com.yayatechandinnovations.yayaagentic.profile;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.List;
import java.util.Map;

/**
 * Declarative, versioned role bundle. References tools, knowledge sources,
 * and an auth binding; never embeds implementations. See design §5.2.
 */
public record Profile(
        Ids.ProfileId id,
        Ids.TenantId tenant,
        String displayName,
        String introOneLiner,
        String systemPromptFragment,
        List<Ids.CapabilityId> capabilities,
        List<Ids.KnowledgeSourceId> knowledgeSources,
        Ids.AuthBindingId authBinding,
        Map<String, Object> metadata
) {}
