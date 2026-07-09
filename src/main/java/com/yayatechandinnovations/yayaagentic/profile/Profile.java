package com.yayatechandinnovations.yayaagentic.profile;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 *
 * <p>{@code commerce} is nullable: a profile is a store <i>because</i> it carries
 * a {@link CommerceBinding} (its pointer at a yaya-sales store), not because of
 * a mode flag. Null for every non-commerce profile. The commerce domain itself
 * lives across the HTTP seam in yaya-sales (yaya-sales design §12.3).</p>
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
        Map<String, Object> metadata,
        CommerceBinding commerce,
        Ids.StartFlowId defaultStartFlowId
) {
    public Profile {
        if (language == null || language.isBlank()) language = "en";
    }

    /** Back-compat: a non-commerce profile with no default start flow. */
    public Profile(Ids.ProfileId id, Ids.TenantId tenant, String displayName, String introOneLiner,
                   String systemPromptFragment, List<Ids.CapabilityId> capabilities,
                   List<Ids.KnowledgeSourceId> knowledgeSources, Ids.AuthBindingId authBinding,
                   String language, Map<String, Object> metadata) {
        this(id, tenant, displayName, introOneLiner, systemPromptFragment, capabilities,
                knowledgeSources, authBinding, language, metadata, null, null);
    }

    /** Back-compat: a profile with commerce but no default start flow. */
    public Profile(Ids.ProfileId id, Ids.TenantId tenant, String displayName, String introOneLiner,
                   String systemPromptFragment, List<Ids.CapabilityId> capabilities,
                   List<Ids.KnowledgeSourceId> knowledgeSources, Ids.AuthBindingId authBinding,
                   String language, Map<String, Object> metadata, CommerceBinding commerce) {
        this(id, tenant, displayName, introOneLiner, systemPromptFragment, capabilities,
                knowledgeSources, authBinding, language, metadata, commerce, null);
    }

    /** True when this profile is composed into a conversational storefront. */
    @JsonIgnore
    public boolean isStore() {
        return commerce != null && commerce.cartEnabled();
    }
}
