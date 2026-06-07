package com.yayatechandinnovations.yayaagentic.knowledge.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.knowledge.IngestionPolicy;
import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeLoader;
import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeSource;
import com.yayatechandinnovations.yayaagentic.knowledge.chunker.Chunker;
import com.yayatechandinnovations.yayaagentic.knowledge.embedding.EmbeddingService;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeChunkRepository;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeDocumentEntity;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeDocumentRepository;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeSourceEntity;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Drives one ingest run for a single {@link KnowledgeSource}:
 * loader → chunker → embedder → upsert. Idempotent on
 * {@code (tenant, source, source_version, content_hash)} — re-ingesting
 * the same content is a no-op aside from touching {@code indexed_at}.
 * <p>
 * Real fan-out scheduling (Spring {@code @Scheduled} per-source refresh
 * intervals) is M2.5-B; for M2.5-A admins trigger reindex explicitly via
 * the REST surface or the bootstrap calls this directly at startup.
 */
@Component
public class IngestionOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionOrchestrator.class);

    private final List<KnowledgeLoader> loaders;
    private final Chunker chunker;
    private final EmbeddingService embeddings;
    private final KnowledgeSourceRepository sourceRepo;
    private final KnowledgeDocumentRepository documentRepo;
    private final KnowledgeChunkRepository chunkRepo;
    private final ContentSanitizer sanitizer;
    private final ObjectMapper json;

    public IngestionOrchestrator(List<KnowledgeLoader> loaders,
                                 Chunker chunker,
                                 EmbeddingService embeddings,
                                 KnowledgeSourceRepository sourceRepo,
                                 KnowledgeDocumentRepository documentRepo,
                                 KnowledgeChunkRepository chunkRepo,
                                 ContentSanitizer sanitizer,
                                 ObjectMapper json) {
        this.loaders = loaders;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.sourceRepo = sourceRepo;
        this.documentRepo = documentRepo;
        this.chunkRepo = chunkRepo;
        this.sanitizer = sanitizer;
        this.json = json;
    }

    public IngestResult ingest(KnowledgeSource source) {
        long start = System.nanoTime();
        KnowledgeLoader loader = loaders.stream()
                .filter(l -> l.supports(source.location()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no loader for " + source.location().getClass().getSimpleName()));

        markSourceStatus(source, "INDEXING", null, null);

        int docsAdded = 0;
        int chunksAdded = 0;
        Throwable failure = null;
        IngestionPolicy policy = source.ingestion() == null
                ? IngestionPolicy.defaults() : source.ingestion();

        try (var stream = loader.load(source.location(),
                new KnowledgeLoader.IngestionContext("ingest-" + System.currentTimeMillis(), Map.of()))) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                var raw = iterator.next();
                IngestCounts c = ingestOneDocument(source, raw, policy);
                if (c.docInserted) docsAdded++;
                chunksAdded += c.chunksWritten;
            }
        } catch (Throwable t) {
            failure = t;
            LOG.warn("ingest failed for source {}: {}", source.id().value(), t.getMessage());
        }

        OffsetDateTime now = OffsetDateTime.now();
        long totalChunks = chunkRepo.countByTenantSource(
                source.tenant().value(), source.id().value(), source.version());
        long totalDocs = documentRepo.countByTenantIdAndSourceIdAndSourceVersion(
                source.tenant().value(), source.id().value(), source.version());

        markSourceStatus(source, failure == null ? "READY" : "ERROR",
                failure == null ? null : failure.getMessage(), now);
        updateSourceCounts(source, (int) totalDocs, (int) totalChunks);

        long ms = (System.nanoTime() - start) / 1_000_000;
        LOG.info("ingest source={} added {} docs, {} new chunks, total chunks={} ({} ms)",
                source.id().value(), docsAdded, chunksAdded, totalChunks, ms);

        return new IngestResult((int) totalDocs, (int) totalChunks, docsAdded,
                chunksAdded, failure == null ? null : failure.getMessage());
    }

    @Transactional
    protected IngestCounts ingestOneDocument(KnowledgeSource source,
                                             KnowledgeLoader.RawDocument raw,
                                             IngestionPolicy policy) {
        String contentHash = sha256Hex(raw.text());
        var existing = documentRepo.findByTenantIdAndSourceIdAndSourceVersionAndContentHash(
                source.tenant().value(), source.id().value(), source.version(), contentHash);

        if (existing.isPresent()) {
            existing.get().setIndexedAt(OffsetDateTime.now());
            documentRepo.save(existing.get());
            return new IngestCounts(false, 0);
        }

        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity(
                source.tenant().value(), source.id().value(), source.version(),
                raw.uri(), raw.title(), contentHash);
        doc.setIndexedAt(OffsetDateTime.now());
        doc.setMetadataJson(writeJson(raw.metadata() == null ? Map.of() : raw.metadata()));
        // saveAndFlush pushes the INSERT to PG immediately so the JdbcTemplate
        // chunk writes below can see the row via the document FK.
        doc = documentRepo.saveAndFlush(doc);

        List<Chunker.Chunk> chunks = chunker.chunk(raw, policy);
        if (chunks.isEmpty()) return new IngestCounts(true, 0);

        // Sanitize chunk-by-chunk: chunking already split the doc on
        // structural boundaries, so per-chunk patterns are cheap and the
        // marker stays localized.
        List<String> sanitizedTexts = chunks.stream()
                .map(c -> sanitizer.sanitize(c.text()))
                .toList();
        List<float[]> vectors = embeddings.embedBatch(sanitizedTexts);

        for (int i = 0; i < chunks.size(); i++) {
            Chunker.Chunk chunk = chunks.get(i);
            String text = sanitizedTexts.get(i);
            float[] embedding = vectors.get(i);
            Map<String, Object> meta = new java.util.HashMap<>(chunk.metadata());
            meta.put("documentId", doc.getId().toString());
            meta.put("documentUri", raw.uri());
            meta.put("documentTitle", raw.title());
            meta.put("embeddingModel", embeddings.modelId());
            chunkRepo.insertChunk(
                    source.tenant().value(), source.id().value(), source.version(),
                    doc.getId(), chunk.index(), text, embedding, writeJson(meta));
        }
        return new IngestCounts(true, chunks.size());
    }

    private void markSourceStatus(KnowledgeSource source, String status, String error, OffsetDateTime lastIndexed) {
        KnowledgeSourceEntity.PK pk = new KnowledgeSourceEntity.PK(
                source.tenant().value(), source.id().value(), source.version());
        sourceRepo.findById(pk).ifPresent(e -> {
            e.setStatus(status);
            e.setLastError(error);
            if (lastIndexed != null) e.setLastIndexedAt(lastIndexed);
            sourceRepo.save(e);
        });
    }

    private void updateSourceCounts(KnowledgeSource source, int docs, int chunks) {
        KnowledgeSourceEntity.PK pk = new KnowledgeSourceEntity.PK(
                source.tenant().value(), source.id().value(), source.version());
        sourceRepo.findById(pk).ifPresent(e -> {
            e.setDocCount(docs);
            e.setChunkCount(chunks);
            sourceRepo.save(e);
        });
    }

    private String writeJson(Object v) {
        try { return json.writeValueAsString(v); }
        catch (Exception ex) { return "{}"; }
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record IngestCounts(boolean docInserted, int chunksWritten) {}

    public record IngestResult(int totalDocs, int totalChunks, int docsAdded,
                               int chunksAdded, String error) {}
}
