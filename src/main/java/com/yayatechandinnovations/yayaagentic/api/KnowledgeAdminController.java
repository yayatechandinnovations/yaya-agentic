package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.dto.KnowledgeAdminDtos;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.knowledge.IngestionPolicy;
import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeSource;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalPolicy;
import com.yayatechandinnovations.yayaagentic.knowledge.SourceLocation;
import com.yayatechandinnovations.yayaagentic.knowledge.ingest.IngestionOrchestrator;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeSourceEntity;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeSourceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin REST for knowledge sources. Mirrors {@link AdminController}'s
 * "POST creates a new version" convention: an update bumps the version
 * row and leaves the prior one in place (so anything that bound to the
 * old version keeps working until the operator re-binds).
 * <p>
 * Reindex is synchronous in M2.5 — small corpora, the admin sees the
 * outcome immediately. Background ingestion with a job queue is M3.
 */
@RestController
@RequestMapping("/v1/admin/knowledge-sources")
public class KnowledgeAdminController {

    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};

    private final KnowledgeSourceRepository sources;
    private final IngestionOrchestrator ingestion;
    private final M0Catalog runtimeCatalog;
    private final ObjectMapper json;

    public KnowledgeAdminController(KnowledgeSourceRepository sources,
                                    IngestionOrchestrator ingestion,
                                    M0Catalog runtimeCatalog,
                                    ObjectMapper json) {
        this.sources = sources;
        this.ingestion = ingestion;
        this.runtimeCatalog = runtimeCatalog;
        this.json = json;
    }

    @GetMapping
    public KnowledgeAdminDtos.ListResponse list(
            @RequestParam(value = "tenant", defaultValue = "default") String tenant) {
        var items = sources.findByTenantId(tenant).stream()
                .map(this::toView)
                .toList();
        return new KnowledgeAdminDtos.ListResponse(items);
    }

    @GetMapping("/{id}")
    public KnowledgeAdminDtos.KnowledgeSourceView get(
            @RequestParam(value = "tenant", defaultValue = "default") String tenant,
            @PathVariable("id") String id) {
        return toView(findLatestOrThrow(tenant, id));
    }

    @PostMapping
    @Transactional
    public KnowledgeAdminDtos.KnowledgeSourceView create(
            @RequestBody KnowledgeAdminDtos.CreateKnowledgeSourceRequest req) {
        String tenant = req.tenant() == null ? "default" : req.tenant();
        int version = sources.findByTenantId(tenant).stream()
                .filter(e -> e.getId().equals(req.id()))
                .mapToInt(KnowledgeSourceEntity::getVersion)
                .max().orElse(0) + 1;

        KnowledgeSourceEntity entity = new KnowledgeSourceEntity(
                tenant, req.id(), version, req.name(),
                req.locationKind(),
                writeJson(req.location() == null ? Map.of() : req.location()),
                writeJson(req.ingestion() == null ? Map.of() : req.ingestion()),
                writeJson(req.retrieval() == null ? Map.of() : req.retrieval()));
        entity.setAccessRequirementJson(
                writeJson(req.access() == null ? Map.of() : req.access()));
        sources.save(entity);

        // Mirror into runtime catalog so retrieval picks it up without a
        // restart, matching how AdminController handles tools/profiles.
        runtimeCatalog.registerKnowledgeSource(rehydrate(entity));
        return toView(entity);
    }

    @PutMapping("/{id}")
    @Transactional
    public KnowledgeAdminDtos.KnowledgeSourceView update(
            @RequestParam(value = "tenant", defaultValue = "default") String tenant,
            @PathVariable("id") String id,
            @RequestBody KnowledgeAdminDtos.UpdateKnowledgeSourceRequest req) {
        int nextVersion = sources.findByTenantId(tenant).stream()
                .filter(e -> e.getId().equals(id))
                .mapToInt(KnowledgeSourceEntity::getVersion)
                .max().orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no such source: " + id)) + 1;

        KnowledgeSourceEntity entity = new KnowledgeSourceEntity(
                tenant, id, nextVersion,
                req.name(), req.locationKind(),
                writeJson(req.location() == null ? Map.of() : req.location()),
                writeJson(req.ingestion() == null ? Map.of() : req.ingestion()),
                writeJson(req.retrieval() == null ? Map.of() : req.retrieval()));
        entity.setAccessRequirementJson(
                writeJson(req.access() == null ? Map.of() : req.access()));
        sources.save(entity);

        runtimeCatalog.registerKnowledgeSource(rehydrate(entity));
        return toView(entity);
    }

    @PostMapping("/{id}/reindex")
    public KnowledgeAdminDtos.ReindexResponse reindex(
            @RequestParam(value = "tenant", defaultValue = "default") String tenant,
            @PathVariable("id") String id) {
        KnowledgeSourceEntity entity = findLatestOrThrow(tenant, id);
        KnowledgeSource source = rehydrate(entity);
        var result = ingestion.ingest(source);
        // Re-register in case ingest updated counts/status indirectly.
        runtimeCatalog.registerKnowledgeSource(source);
        return new KnowledgeAdminDtos.ReindexResponse(
                result.docsAdded(), result.chunksAdded(),
                result.totalDocs(), result.totalChunks(),
                result.error());
    }

    @GetMapping("/{id}/status")
    public Map<String, Object> status(
            @RequestParam(value = "tenant", defaultValue = "default") String tenant,
            @PathVariable("id") String id) {
        KnowledgeSourceEntity e = findLatestOrThrow(tenant, id);
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("status", e.getStatus());
        out.put("lastIndexedAt", e.getLastIndexedAt());
        out.put("docCount", e.getDocCount());
        out.put("chunkCount", e.getChunkCount());
        out.put("lastError", e.getLastError());
        return out;
    }

    // ---- internals -------------------------------------------------------

    private KnowledgeSourceEntity findLatestOrThrow(String tenant, String id) {
        return sources.findByTenantId(tenant).stream()
                .filter(e -> e.getId().equals(id))
                .max((a, b) -> Integer.compare(a.getVersion(), b.getVersion()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no such source: " + id));
    }

    private KnowledgeAdminDtos.KnowledgeSourceView toView(KnowledgeSourceEntity e) {
        return KnowledgeAdminDtos.KnowledgeSourceView.of(e, this::readMap);
    }

    private KnowledgeSource rehydrate(KnowledgeSourceEntity e) {
        Map<String, Object> locationMap = readMap(e.getLocationJson());
        SourceLocation location = decodeLocation(e.getLocationKind(), locationMap);
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
                Object rawDocs = raw.get("docs");
                if (rawDocs instanceof List<?> list) {
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
            case "LOCAL_PATH" -> new SourceLocation.LocalPath(
                    Path.of(String.valueOf(raw.getOrDefault("root", "."))),
                    (String) raw.get("includeGlob"),
                    (String) raw.get("excludeGlob"));
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
        // Full editor for PermissionRequirement (scope sets, attribute
        // matches, ownership claim paths) is part of the auth-policy admin
        // surface in M5. Until then the source admin endpoints accept the
        // requirement as opaque metadata and pin it to none() at runtime —
        // sources are gated by the binding (only profiles that attach a
        // source can read it) plus tenant scoping.
        return PermissionRequirement.none();
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception ex) { return "{}"; }
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try { return json.readValue(raw, MAP_REF); }
        catch (Exception ex) { return Map.of(); }
    }
}
