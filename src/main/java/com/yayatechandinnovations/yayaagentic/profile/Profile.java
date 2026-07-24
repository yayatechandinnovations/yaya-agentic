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
 *
 * <p>{@code extensions} is the framework's domain-neutral seam for app-specific
 * traits: a map of namespaced <i>kind</i> ({@code "sales.store"}) → an opaque
 * payload the framework never interprets. The engine surfaces it verbatim to the
 * client as start-time trait hints (see {@code StartSessionResult.clientTraits})
 * so an app can render its own chrome — e.g. yaya-sales publishes a
 * {@code sales.store} trait whose opaque payload lights up its own affordances.
 * The business domain lives across the HTTP seam in the app, never here (CLAUDE.md
 * domain-neutrality; yaya-sales design §11.4/§12.3).</p>
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
        Map<String, Object> extensions,
        Ids.StartFlowId defaultStartFlowId
) {
    public Profile {
        if (language == null || language.isBlank()) language = "en";
        if (extensions == null) extensions = Map.of();
    }

    /** Back-compat: a profile with no extensions and no default start flow. */
    public Profile(Ids.ProfileId id, Ids.TenantId tenant, String displayName, String introOneLiner,
                   String systemPromptFragment, List<Ids.CapabilityId> capabilities,
                   List<Ids.KnowledgeSourceId> knowledgeSources, Ids.AuthBindingId authBinding,
                   String language, Map<String, Object> metadata) {
        this(id, tenant, displayName, introOneLiner, systemPromptFragment, capabilities,
                knowledgeSources, authBinding, language, metadata, Map.of(), null);
    }
}
