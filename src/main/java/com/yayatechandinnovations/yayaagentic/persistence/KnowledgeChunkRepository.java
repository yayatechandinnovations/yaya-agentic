package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Chunks live outside JPA because the {@code embedding vector(1536)} column
 * is awkward to map through Hibernate. We use plain JdbcTemplate: pgvector
 * accepts the string literal form {@code [v1,v2,…]} and we cast it explicitly.
 */
@Repository
public class KnowledgeChunkRepository {

    private final JdbcTemplate jdbc;

    public KnowledgeChunkRepository(@Qualifier("jdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertChunk(String tenantId, String sourceId, int sourceVersion,
                            UUID documentId, int chunkIndex, String text,
                            float[] embedding, String metadataJson) {
        jdbc.update(
                "INSERT INTO knowledge_chunks "
                        + "(source_id, source_version, document_id, tenant_id, "
                        + " chunk_index, text, embedding, metadata_json, tsv) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS vector), CAST(? AS jsonb), "
                        + "        to_tsvector('english', ?)) "
                        + "ON CONFLICT (document_id, chunk_index) DO UPDATE SET "
                        + " text = EXCLUDED.text, "
                        + " embedding = EXCLUDED.embedding, "
                        + " metadata_json = EXCLUDED.metadata_json, "
                        + " tsv = EXCLUDED.tsv",
                sourceId, sourceVersion, documentId, tenantId, chunkIndex,
                text, toVectorLiteral(embedding), metadataJson, text);
    }

    public int deleteByDocument(UUID documentId) {
        return jdbc.update("DELETE FROM knowledge_chunks WHERE document_id = ?", documentId);
    }

    public long countByTenantSource(String tenantId, String sourceId, int sourceVersion) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_chunks "
                        + "WHERE tenant_id = ? AND source_id = ? AND source_version = ?",
                Long.class, tenantId, sourceId, sourceVersion);
        return n == null ? 0L : n;
    }

    /**
     * Cosine-distance ANN search. Filters by tenant + the caller-supplied
     * eligible source IDs (post-AuthZ filtering happens in the caller). The
     * returned score is {@code 1 - cosine_distance} so higher = closer.
     */
    public List<ChunkRow> annSearch(String tenantId, List<SourceKey> sources,
                                    float[] queryEmbedding, int topK) {
        if (sources == null || sources.isEmpty()) return List.of();

        StringBuilder sourceFilter = new StringBuilder("(");
        for (int i = 0; i < sources.size(); i++) {
            if (i > 0) sourceFilter.append(" OR ");
            sourceFilter.append("(c.source_id = ? AND c.source_version = ?)");
        }
        sourceFilter.append(")");

        Object[] args = new Object[2 + sources.size() * 2 + 2];
        int idx = 0;
        args[idx++] = toVectorLiteral(queryEmbedding);
        args[idx++] = tenantId;
        for (SourceKey s : sources) {
            args[idx++] = s.sourceId();
            args[idx++] = s.version();
        }
        args[idx++] = toVectorLiteral(queryEmbedding);
        args[idx] = topK;

        String sql = "SELECT c.id, c.source_id, c.source_version, c.document_id, "
                + "       c.chunk_index, c.text, c.metadata_json::text, "
                + "       1 - (c.embedding <=> CAST(? AS vector)) AS score "
                + "FROM knowledge_chunks c "
                + "WHERE c.tenant_id = ? AND c.embedding IS NOT NULL "
                + "  AND " + sourceFilter + " "
                + "ORDER BY c.embedding <=> CAST(? AS vector) "
                + "LIMIT ?";

        return jdbc.query(sql, (rs, n) -> new ChunkRow(
                (UUID) rs.getObject("id"),
                rs.getString("source_id"),
                rs.getInt("source_version"),
                (UUID) rs.getObject("document_id"),
                rs.getInt("chunk_index"),
                rs.getString("text"),
                rs.getString("metadata_json"),
                rs.getDouble("score")
        ), args);
    }

    private static String toVectorLiteral(float[] v) {
        if (v == null || v.length == 0) return "[]";
        StringBuilder sb = new StringBuilder(v.length * 10);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    public record SourceKey(String sourceId, int version) {}

    public record ChunkRow(
            UUID id,
            String sourceId,
            int sourceVersion,
            UUID documentId,
            int chunkIndex,
            String text,
            String metadataJson,
            double score
    ) {}
}
