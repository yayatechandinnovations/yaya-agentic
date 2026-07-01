package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.dto.KnowledgeAdminDtos;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeSource;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.BlobStore;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.KnowledgeUploadService;
import com.yayatechandinnovations.yayaagentic.knowledge.ingest.IngestionJobService;
import com.yayatechandinnovations.yayaagentic.knowledge.ingest.IngestionOrchestrator;
import com.yayatechandinnovations.yayaagentic.knowledge.ingest.KnowledgeSourceRehydrator;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeIngestionJobRepository;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeSourceEntity;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeSourceRepository;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/**
 * Admin REST for knowledge sources. Mirrors {@link AdminController}'s
 * "POST creates a new version" convention: an update bumps the version
 * row and leaves the prior one in place (so anything that bound to the
 * old version keeps working until the operator re-binds).
 * <p>
 * Two ingestion paths: {@code /reindex} runs synchronously (the admin sees
 * counts immediately, good for small inline/local corpora); uploaded sources
 * auto-enqueue a durable async job on create/update (design §9) that a worker
 * pool drains in the background, with progress visible via {@code /status}.
 */
@RestController
@RequestMapping("/v1/admin/knowledge-sources")
public class KnowledgeAdminController {

    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};

    private final KnowledgeSourceRepository sources;
    private final IngestionOrchestrator ingestion;
    private final M0Catalog runtimeCatalog;
    private final ObjectMapper json;
    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final KnowledgeUploadService uploads;
    private final BlobStore blobStore;
    private final KnowledgeSourceRehydrator rehydrator;
    private final IngestionJobService jobService;
    private final KnowledgeIngestionJobRepository jobs;

    public KnowledgeAdminController(KnowledgeSourceRepository sources,
                                    IngestionOrchestrator ingestion,
                                    M0Catalog runtimeCatalog,
                                    ObjectMapper json,
                                    TenantGuard tenantGuard,
                                    WorkspaceAccessHelper workspaceAccess,
                                    KnowledgeUploadService uploads,
                                    BlobStore blobStore,
                                    KnowledgeSourceRehydrator rehydrator,
                                    IngestionJobService jobService,
                                    KnowledgeIngestionJobRepository jobs) {
        this.sources = sources;
        this.ingestion = ingestion;
        this.runtimeCatalog = runtimeCatalog;
        this.json = json;
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.uploads = uploads;
        this.blobStore = blobStore;
        this.rehydrator = rehydrator;
        this.jobService = jobService;
        this.jobs = jobs;
    }

    @GetMapping
    public KnowledgeAdminDtos.ListResponse list(
            @RequestParam(value = "tenant", required = false) String tenant,
            ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.VIEWER);
        var items = sources.findByTenantId(resolved).stream()
                .map(this::toView)
                .toList();
        return new KnowledgeAdminDtos.ListResponse(items);
    }

    @GetMapping("/{id}")
    public KnowledgeAdminDtos.KnowledgeSourceView get(
            @RequestParam(value = "tenant", required = false) String tenant,
            @PathVariable("id") String id,
            ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.VIEWER);
        return toView(findLatestOrThrow(resolved, id));
    }

    @PostMapping
    @Transactional
    public KnowledgeAdminDtos.KnowledgeSourceView create(
            @RequestBody KnowledgeAdminDtos.CreateKnowledgeSourceRequest req,
            ServerWebExchange exchange) {
        String tenant = workspaceAccess.require(req.tenant(), exchange, Role.MEMBER);
        tenantGuard.requireWritable(tenant);
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
        registerAndMaybeEnqueue(entity);
        return toView(entity);
    }

    @PutMapping("/{id}")
    @Transactional
    public KnowledgeAdminDtos.KnowledgeSourceView update(
            @RequestParam(value = "tenant", required = false) String tenant,
            @PathVariable("id") String id,
            @RequestBody KnowledgeAdminDtos.UpdateKnowledgeSourceRequest req,
            ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.MEMBER);
        tenantGuard.requireWritable(resolved);
        int nextVersion = sources.findByTenantId(resolved).stream()
                .filter(e -> e.getId().equals(id))
                .mapToInt(KnowledgeSourceEntity::getVersion)
                .max().orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "no such source: " + id)) + 1;

        KnowledgeSourceEntity entity = new KnowledgeSourceEntity(
                resolved, id, nextVersion,
                req.name(), req.locationKind(),
                writeJson(req.location() == null ? Map.of() : req.location()),
                writeJson(req.ingestion() == null ? Map.of() : req.ingestion()),
                writeJson(req.retrieval() == null ? Map.of() : req.retrieval()));
        entity.setAccessRequirementJson(
                writeJson(req.access() == null ? Map.of() : req.access()));
        sources.save(entity);

        registerAndMaybeEnqueue(entity);
        return toView(entity);
    }

    @PostMapping("/{id}/reindex")
    public KnowledgeAdminDtos.ReindexResponse reindex(
            @RequestParam(value = "tenant", required = false) String tenant,
            @PathVariable("id") String id,
            ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.MEMBER);
        KnowledgeSourceEntity entity = findLatestOrThrow(resolved, id);
        KnowledgeSource source = rehydrator.rehydrate(entity);
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
            @RequestParam(value = "tenant", required = false) String tenant,
            @PathVariable("id") String id,
            ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.VIEWER);
        KnowledgeSourceEntity e = findLatestOrThrow(resolved, id);
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("status", e.getStatus());
        out.put("lastIndexedAt", e.getLastIndexedAt());
        out.put("docCount", e.getDocCount());
        out.put("chunkCount", e.getChunkCount());
        out.put("lastError", e.getLastError());
        // Surface the latest async ingestion job so the console can show
        // QUEUED/RUNNING/DONE/FAILED + retry state while indexing runs.
        jobs.findFirstByTenantIdAndSourceIdOrderByCreatedAtDesc(resolved, id).ifPresent(job -> {
            out.put("jobState", job.getState());
            out.put("jobAttempts", job.getAttempts());
            out.put("jobMaxAttempts", job.getMaxAttempts());
            out.put("jobRunAfter", job.getRunAfter());
            out.put("jobLastError", job.getLastError());
        });
        return out;
    }

    /**
     * Upload one or more files. Bytes are scanned, sniffed, allow-listed, and
     * stored; the response gives the operator UI the {@code blobId} references
     * to drop into a source's {@code location.files}. Multipart (not base64) —
     * documents are the large-file case (design §10). All blocking work
     * (blob read, scan, extract-free store, JDBC) runs on {@code boundedElastic}
     * so the Netty event loop is never blocked.
     */
    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<KnowledgeAdminDtos.UploadResponse> upload(
            @RequestParam(value = "tenant", required = false) String tenant,
            @RequestPart("files") Flux<FilePart> files,
            ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.MEMBER);
        tenantGuard.requireWritable(resolved);

        return files.flatMapSequential(part -> DataBufferUtils.join(part.content())
                        .map(buffer -> new KnowledgeUploadService.PendingFile(
                                part.filename(), toBytes(buffer))))
                .collectList()
                .publishOn(Schedulers.boundedElastic())
                .map(pending -> {
                    var stored = uploads.storeAll(new TenantId(resolved), pending);
                    return new KnowledgeAdminDtos.UploadResponse(stored.stream()
                            .map(s -> new KnowledgeAdminDtos.UploadedFileView(
                                    s.blobId(), s.filename(), s.contentType(),
                                    s.sizeBytes(), s.sha256()))
                            .toList());
                });
    }

    @DeleteMapping("/uploads/{blobId}")
    public Mono<Void> deleteUpload(
            @RequestParam(value = "tenant", required = false) String tenant,
            @PathVariable("blobId") String blobId,
            ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.MEMBER);
        tenantGuard.requireWritable(resolved);
        return Mono.fromRunnable(() -> blobStore.delete(new TenantId(resolved), blobId))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    private static byte[] toBytes(DataBuffer buffer) {
        try {
            byte[] out = new byte[buffer.readableByteCount()];
            buffer.read(out);
            return out;
        } finally {
            DataBufferUtils.release(buffer);
        }
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

    /**
     * Register the source in the runtime catalog and, for uploaded sources,
     * enqueue a durable async ingestion job (design §9). Inline/local-path
     * sources keep the explicit {@code /reindex} trigger; uploaded documents
     * index in the background so a large corpus never blocks the admin call.
     */
    private void registerAndMaybeEnqueue(KnowledgeSourceEntity entity) {
        runtimeCatalog.registerKnowledgeSource(rehydrator.rehydrate(entity));
        if ("UPLOADED".equalsIgnoreCase(entity.getLocationKind())) {
            jobService.enqueue(entity.getTenantId(), entity.getId(), entity.getVersion());
        }
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
