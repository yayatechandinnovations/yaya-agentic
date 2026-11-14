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
        Ids.StartFlowId defaultStartFlowId,
        Runtime runtime
) {
    /** The deployment-wide tool-round ceiling a profile gets unless it sets its own. */
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 5;
    public static final int MIN_TOOL_ROUNDS = 1;
    public static final int MAX_TOOL_ROUNDS = 16;

    public Profile {
        if (language == null || language.isBlank()) language = "en";
        if (extensions == null) extensions = Map.of();
        if (runtime == null) runtime = Runtime.defaults();
    }

    /**
     * How this profile's turns run. {@code model} null = the deployment's model;
     * {@code maxToolRounds} null = {@link #DEFAULT_MAX_TOOL_ROUNDS}. A
     * {@code metered} profile's turns count against the tenant's monthly
     * allowance and are refused once it is used up; an unmetered profile is
     * never blocked by it.
     */
    public record Runtime(String model, Integer maxToolRounds, boolean metered) {
        public static Runtime defaults() {
            return new Runtime(null, null, false);
        }
    }

    public int effectiveMaxToolRounds() {
        Integer rounds = runtime.maxToolRounds();
        if (rounds == null) return DEFAULT_MAX_TOOL_ROUNDS;
        return Math.max(MIN_TOOL_ROUNDS, Math.min(MAX_TOOL_ROUNDS, rounds));
    }

    /** Back-compat: a profile on the deployment's model and tool-round ceiling, unmetered. */
    public Profile(Ids.ProfileId id, Ids.TenantId tenant, String displayName, String introOneLiner,
                   String systemPromptFragment, List<Ids.CapabilityId> capabilities,
                   List<Ids.KnowledgeSourceId> knowledgeSources, Ids.AuthBindingId authBinding,
                   String language, Map<String, Object> metadata, Map<String, Object> extensions,
                   Ids.StartFlowId defaultStartFlowId) {
        this(id, tenant, displayName, introOneLiner, systemPromptFragment, capabilities,
                knowledgeSources, authBinding, language, metadata, extensions, defaultStartFlowId, null);
    }

    /** Back-compat: a profile with no extensions and no default start flow. */
    public Profile(Ids.ProfileId id, Ids.TenantId tenant, String displayName, String introOneLiner,
                   String systemPromptFragment, List<Ids.CapabilityId> capabilities,
                   List<Ids.KnowledgeSourceId> knowledgeSources, Ids.AuthBindingId authBinding,
                   String language, Map<String, Object> metadata) {
        this(id, tenant, displayName, introOneLiner, systemPromptFragment, capabilities,
                knowledgeSources, authBinding, language, metadata, Map.of(), null, null);
    }
}
