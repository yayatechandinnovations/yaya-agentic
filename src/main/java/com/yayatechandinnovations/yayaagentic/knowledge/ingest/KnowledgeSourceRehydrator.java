package com.yayatechandinnovations.yayaagentic.knowledge.ingest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.knowledge.IngestionPolicy;
import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeSource;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalPolicy;
import com.yayatechandinnovations.yayaagentic.knowledge.SourceLocation;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeSourceEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a persisted {@link KnowledgeSourceEntity} (JSON-encoded columns) back
 * into a runtime {@link KnowledgeSource}. Extracted from
 * {@code KnowledgeAdminController} so both the admin surface and the async
 * {@code IngestionWorker} decode sources the same way — the worker rehydrates a
 * source before running the pipeline, and there must be exactly one decoder.
 */
@Component
public class KnowledgeSourceRehydrator {

    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};

    private final ObjectMapper json;

    public KnowledgeSourceRehydrator(ObjectMapper json) {
        this.json = json;
    }

    public KnowledgeSource rehydrate(KnowledgeSourceEntity e) {
        SourceLocation location = decodeLocation(e.getLocationKind(), readMap(e.getLocationJson()));
        IngestionPolicy ingest = decodeIngestionPolicy(readMap(e.getIngestionPolicyJson()));
        RetrievalPolicy retrieval = decodeRetrievalPolicy(readMap(e.getRetrievalPolicyJson()));
        PermissionRequirement access = decodeAccess(readMap(e.getAccessRequirementJson()));
        return new KnowledgeSource(
                new Ids.KnowledgeSourceId(e.getId()),
                new Ids.TenantId(e.getTenantId()),
                e.getName(),
                location,
                ingest,
                retrieval,
                access,
                e.getVersion());
    }

    private SourceLocation decodeLocation(String kind, Map<String, Object> raw) {
        if (kind == null) return new SourceLocation.Inline(List.of());
        return switch (kind.toUpperCase()) {
            case "INLINE" -> {
                List<SourceLocation.Inline.DocumentBlob> docs = new ArrayList<>();
                if (raw.get("docs") instanceof List<?> list) {
                    for (Object d : list) {
                        if (d instanceof Map<?, ?> m) {
                            Object contentType = m.get("contentType");
                            Object text = m.get("text");
                            docs.add(new SourceLocation.Inline.DocumentBlob(
                                    String.valueOf(m.get("id")),
                                    contentType == null ? "text/plain" : contentType.toString(),
                                    text == null ? "" : text.toString()));
                        }
                    }
                }
                yield new SourceLocation.Inline(docs);
            }
            case "UPLOADED" -> {
                List<SourceLocation.Uploaded.UploadedFile> files = new ArrayList<>();
                if (raw.get("files") instanceof List<?> list) {
                    for (Object f : list) {
                        if (f instanceof Map<?, ?> m) {
                            files.add(new SourceLocation.Uploaded.UploadedFile(
                                    String.valueOf(m.get("blobId")),
                                    String.valueOf(m.get("filename")),
                                    String.valueOf(m.get("contentType")),
                                    m.get("sizeBytes") instanceof Number n ? n.longValue() : 0L,
                                    String.valueOf(m.get("sha256"))));
                        }
                    }
                }
                yield new SourceLocation.Uploaded(files);
            }
            // Removed for security — reading operator-supplied server paths is an
            // arbitrary-file-read risk. Legacy rows get a clear, specific error.
            case "LOCAL_PATH" -> throw new ResponseStatusException(HttpStatus.GONE,
                    "local-path knowledge sources were removed for security; "
                            + "re-create this source as an upload");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported location kind: " + kind);
        };
    }

    private IngestionPolicy decodeIngestionPolicy(Map<String, Object> raw) {
        if (raw.isEmpty()) return IngestionPolicy.defaults();
        return new IngestionPolicy(
                String.valueOf(raw.getOrDefault("chunkerName", "recursive-structural")),
                ((Number) raw.getOrDefault("chunkSize", 900)).intValue(),
                ((Number) raw.getOrDefault("chunkOverlap", 100)).intValue(),
                String.valueOf(raw.getOrDefault("embeddingModel", "text-embedding-3-small")),
                Duration.ofHours(((Number) raw.getOrDefault("refreshIntervalHours", 24L)).longValue()));
    }

    private RetrievalPolicy decodeRetrievalPolicy(Map<String, Object> raw) {
        if (raw.isEmpty()) return RetrievalPolicy.defaults();
        return new RetrievalPolicy(
                ((Number) raw.getOrDefault("topK", 6)).intValue(),
                ((Number) raw.getOrDefault("minScore", 0.0)).doubleValue(),
                ((Number) raw.getOrDefault("vectorWeight", 0.7)).doubleValue(),
                ((Number) raw.getOrDefault("keywordWeight", 0.3)).doubleValue(),
                Boolean.TRUE.equals(raw.get("rerank")));
    }

    private PermissionRequirement decodeAccess(Map<String, Object> raw) {
        // Full PermissionRequirement editing is the M5 auth-policy surface; until
        // then sources are gated by binding + tenant scoping, so runtime access
        // pins to none(). (Same rationale the controller carried previously.)
        return PermissionRequirement.none();
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try { return json.readValue(raw, MAP_REF); }
        catch (Exception ex) { return Map.of(); }
    }
}
