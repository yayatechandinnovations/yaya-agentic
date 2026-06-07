package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeSourceEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Admin DTOs for knowledge-source CRUD + reindex. We expose
 * {@code SourceLocation} as a discriminator + free-form payload rather than
 * hard-coding each variant — the operator UI knows which fields to show
 * per kind, and the backend rehydrates via the sealed interface.
 */
public final class KnowledgeAdminDtos {

    private KnowledgeAdminDtos() {}

    public record CreateKnowledgeSourceRequest(
            String tenant,
            String id,
            String name,
            String locationKind,            // INLINE | LOCAL_PATH
            Map<String, Object> location,    // shape per kind
            Map<String, Object> ingestion,
            Map<String, Object> retrieval,
            Map<String, Object> access       // PermissionRequirement
    ) {}

    public record UpdateKnowledgeSourceRequest(
            String name,
            String locationKind,
            Map<String, Object> location,
            Map<String, Object> ingestion,
            Map<String, Object> retrieval,
            Map<String, Object> access
    ) {}

    public record KnowledgeSourceView(
            String tenant,
            String id,
            int version,
            String name,
            String locationKind,
            Map<String, Object> location,
            Map<String, Object> ingestion,
            Map<String, Object> retrieval,
            Map<String, Object> access,
            String status,
            OffsetDateTime lastIndexedAt,
            int docCount,
            int chunkCount,
            String lastError,
            OffsetDateTime createdAt
    ) {
        public static KnowledgeSourceView of(KnowledgeSourceEntity e,
                                             ObjectReader json) {
            return new KnowledgeSourceView(
                    e.getTenantId(),
                    e.getId(),
                    e.getVersion(),
                    e.getName(),
                    e.getLocationKind(),
                    json.readMap(e.getLocationJson()),
                    json.readMap(e.getIngestionPolicyJson()),
                    json.readMap(e.getRetrievalPolicyJson()),
                    json.readMap(e.getAccessRequirementJson()),
                    e.getStatus(),
                    e.getLastIndexedAt(),
                    e.getDocCount(),
                    e.getChunkCount(),
                    e.getLastError(),
                    e.getCreatedAt());
        }
    }

    public record ReindexResponse(int docsAdded, int chunksAdded,
                                  int totalDocs, int totalChunks, String error) {}

    public record ListResponse(List<KnowledgeSourceView> items) {}

    /** Small adapter so the static-factory method above doesn't need a
     *  Jackson dep at the DTO layer. The controller hands a tiny lambda in. */
    public interface ObjectReader {
        Map<String, Object> readMap(String json);
    }
}
