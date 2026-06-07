package com.yayatechandinnovations.yayaagentic.knowledge.retriever;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.AuthzContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.auth.Authorizer;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeSource;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalContext;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalQuery;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalResult;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievedChunk;
import com.yayatechandinnovations.yayaagentic.knowledge.Retriever;
import com.yayatechandinnovations.yayaagentic.knowledge.embedding.EmbeddingService;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeChunkRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link Retriever}: pgvector cosine ANN, per-source AuthZ via the
 * same {@link Authorizer} chain tools use, untrusted-data semantics
 * upstream in the prompt builder.
 * <p>
 * Source eligibility is evaluated per call: any source whose
 * {@code AccessRequirement} the current principal can't satisfy is
 * <em>silently</em> dropped (and counted in {@code sourcesDenied} for the
 * trace). The retrieved chunks themselves carry no policy back to the LLM
 * — they're just text to ground on.
 * <p>
 * Keyword (tsvector) fusion ships in M2.5-B once vector retrieval is
 * proven. The {@link com.yayatechandinnovations.yayaagentic.knowledge.RetrievalPolicy}
 * {@code keywordWeight} is read but not yet honored.
 */
@Component
public class PgVectorRetriever implements Retriever {

    private final EmbeddingService embeddings;
    private final KnowledgeChunkRepository chunkRepo;
    private final Authorizer authorizer;
    private final M0Catalog catalog;
    private final ObjectMapper json;

    public PgVectorRetriever(EmbeddingService embeddings,
                             KnowledgeChunkRepository chunkRepo,
                             Authorizer authorizer,
                             M0Catalog catalog,
                             ObjectMapper json) {
        this.embeddings = embeddings;
        this.chunkRepo = chunkRepo;
        this.authorizer = authorizer;
        this.catalog = catalog;
        this.json = json;
    }

    @Override
    public RetrievalResult retrieve(RetrievalQuery query, RetrievalContext ctx) {
        Instant t0 = Instant.now();
        if (query == null || query.text() == null || query.text().isBlank()
                || query.sources() == null || query.sources().isEmpty()) {
            return new RetrievalResult(List.of(),
                    new RetrievalResult.Trace(List.of(), List.of(), null, Duration.ZERO));
        }

        List<Ids.KnowledgeSourceId> considered = new ArrayList<>();
        List<Ids.KnowledgeSourceId> denied = new ArrayList<>();
        List<KnowledgeChunkRepository.SourceKey> eligible = new ArrayList<>();
        String tenantId = null;
        int topK = 6;

        for (Ids.KnowledgeSourceId sid : query.sources()) {
            considered.add(sid);
            var maybeSource = catalog.knowledgeSource(sid);
            if (maybeSource.isEmpty()) {
                denied.add(sid);
                continue;
            }
            KnowledgeSource source = maybeSource.get();
            if (tenantId == null) tenantId = source.tenant().value();
            topK = source.retrieval() == null ? topK : source.retrieval().topK();

            AuthzDecision decision = authorizer.authorize(
                    ctx.execution().principal(),
                    source.access(),
                    Map.of("knowledgeSourceId", sid.value()),
                    new AuthzContext(ctx.execution().sessionId(),
                            ctx.execution().turnId(),
                            ctx.execution().traceId(),
                            Map.of("knowledgeSourceId", sid.value())));
            if (decision instanceof AuthzDecision.Deny) {
                denied.add(sid);
                continue;
            }
            eligible.add(new KnowledgeChunkRepository.SourceKey(sid.value(), source.version()));
        }

        if (eligible.isEmpty() || tenantId == null) {
            return new RetrievalResult(List.of(),
                    new RetrievalResult.Trace(considered, denied, null,
                            Duration.between(t0, Instant.now())));
        }

        float[] queryVector = embeddings.embed(query.text());
        var rows = chunkRepo.annSearch(tenantId, eligible, queryVector, topK);

        List<RetrievedChunk> chunks = new ArrayList<>(rows.size());
        for (var row : rows) {
            Map<String, Object> metadata = parseJsonMap(row.metadataJson());
            chunks.add(new RetrievedChunk(
                    row.id().toString(),
                    new Ids.KnowledgeSourceId(row.sourceId()),
                    row.documentId().toString(),
                    row.text(),
                    metadata,
                    row.score()));
        }
        return new RetrievalResult(chunks,
                new RetrievalResult.Trace(considered, denied, query.text(),
                        Duration.between(t0, Instant.now())));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String raw) {
        if (raw == null || raw.isBlank()) return new HashMap<>();
        try { return json.readValue(raw, Map.class); }
        catch (Exception e) { return new HashMap<>(); }
    }
}
