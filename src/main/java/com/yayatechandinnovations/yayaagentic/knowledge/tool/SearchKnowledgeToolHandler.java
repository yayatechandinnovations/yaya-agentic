package com.yayatechandinnovations.yayaagentic.knowledge.tool;

import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.IntentFrame;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalContext;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalQuery;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalResult;
import com.yayatechandinnovations.yayaagentic.knowledge.Retriever;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM-callable tool for explicit knowledge search. Auto-registered for every
 * profile that has at least one knowledge source. The LLM uses this when
 * always-on retrieval missed and it wants to dig deeper, or when the
 * profile is configured for {@code tool-only} retrieval gating.
 * <p>
 * AuthZ for each source still runs inside the retriever; this handler has
 * {@code PermissionRequirement.none()} because there's no whole-tool gate —
 * fine-grained gating happens per source.
 */
@Component("searchKnowledgeTool")
public class SearchKnowledgeToolHandler implements ToolHandler<Map<String, Object>, Map<String, Object>> {

    public static final String TOOL_ID = "search_knowledge";

    public static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"required\":[\"query\"],\"properties\":{"
                    + "\"query\":{\"type\":\"string\",\"description\":\"Natural-language search over attached knowledge sources.\"}"
                    + "}}";

    public static final String OUTPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{"
                    + "\"hits\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
                    + "\"chunkId\":{\"type\":\"string\"},"
                    + "\"source\":{\"type\":\"string\"},"
                    + "\"score\":{\"type\":\"number\"},"
                    + "\"snippet\":{\"type\":\"string\"}}}}}}";

    private final Retriever retriever;
    private final ProfileRegistry profileRegistry;
    private final M0Catalog catalog;

    public SearchKnowledgeToolHandler(Retriever retriever,
                                      ProfileRegistry profileRegistry,
                                      M0Catalog catalog) {
        this.retriever = retriever;
        this.profileRegistry = profileRegistry;
        this.catalog = catalog;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        String queryText = String.valueOf(input == null ? "" : input.getOrDefault("query", ""));
        if (queryText.isBlank()) return Map.of("hits", List.of());

        // We don't have a session→profile lookup on ExecutionContext; the
        // engine binds the active profile per-session out-of-band. For the
        // M2.5-B agentic path the calling profile's sources are reachable
        // via the catalog by re-deriving from the principal's tenant and
        // any source bound to *any* profile the tenant owns. A tighter
        // session→profile resolution lands when M3 wires session state
        // into ExecutionContext explicitly.
        var tenant = ctx.principal() == null ? null : ctx.principal().tenant();
        if (tenant == null) return Map.of("hits", List.of());
        var sources = profileRegistry.listFor(tenant).stream()
                .map(Profile::id)
                .flatMap(pid -> catalog.sourcesForProfile(pid).stream())
                .distinct()
                .toList();
        if (sources.isEmpty()) return Map.of("hits", List.of());

        RetrievalQuery query = new RetrievalQuery(queryText, sources, Map.of());
        RetrievalResult result = retriever.retrieve(query, new RetrievalContext(ctx, IntentFrame.empty()));

        List<Map<String, Object>> hits = new ArrayList<>();
        for (var chunk : result.chunks()) {
            hits.add(Map.of(
                    "chunkId", chunk.chunkId(),
                    "source", chunk.source().value(),
                    "score", chunk.score(),
                    "snippet", chunk.text().length() > 400
                            ? chunk.text().substring(0, 400) + "…"
                            : chunk.text()));
        }
        return Map.of("hits", hits);
    }
}
