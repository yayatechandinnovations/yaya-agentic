package com.yayatechandinnovations.yayaagentic.profile;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.List;
import java.util.Map;

/**
 * Declarative, versioned role bundle. References tools, knowledge sources,
 * and an auth binding; never embeds implementations. See design §5.2.
 *
 * <p>{@code language} is a BCP 47 code ({@code en}, {@code es}, {@code en-US},
 * …) the engine threads into the prompt so the LLM responds in the right
 * language regardless of what the user wrote in. Defaults to {@code en}.</p>
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
        String language,
        Map<String, Object> metadata
) {
    public Profile {
        if (language == null || language.isBlank()) language = "en";
    }
}
