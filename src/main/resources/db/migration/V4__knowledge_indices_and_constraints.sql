-- M2.5: knowledge ingestion now actually writes. Add the indices and
-- uniqueness constraints needed for idempotent reingest + ANN search.

-- Document idempotency: (tenant, source, version, content_hash) is unique
-- so re-ingesting the same content is a no-op upsert.
ALTER TABLE knowledge_documents
    ADD CONSTRAINT knowledge_documents_dedup_uk
    UNIQUE (tenant_id, source_id, source_version, content_hash);

-- Chunk uniqueness within a document (re-chunking replaces; index is the key).
ALTER TABLE knowledge_chunks
    ADD CONSTRAINT knowledge_chunks_doc_index_uk
    UNIQUE (document_id, chunk_index);

-- HNSW index for cosine ANN. Created with default parameters; pgvector lets
-- us tune m / ef_construction later via ALTER INDEX if a real workload
-- demands it. The conditional WHERE skips NULL-embedding rows so the index
-- can be built before ingestion populates anything.
CREATE INDEX IF NOT EXISTS knowledge_chunks_embedding_hnsw_idx
    ON knowledge_chunks USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;

-- Source-level error capture so the admin can see why a reindex failed.
ALTER TABLE knowledge_sources
    ADD COLUMN last_error TEXT;

-- Profile binding: cache the source-list join so the runtime catalog can
-- read sources per profile without re-deriving from the bindings table.
-- (No new table — we already have profile_knowledge_bindings; just adding
-- an index for the lookup path the runtime uses.)
CREATE INDEX IF NOT EXISTS profile_knowledge_bindings_profile_idx
    ON profile_knowledge_bindings (tenant_id, profile_id, profile_version);
