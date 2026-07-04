-- SM-1 media & documents (system-messaging-design.md §10). Logical media assets
-- over the knowledge BlobStore (scanned, sniffed, quota'd, SHA-256 de-duped by
-- the same upload gauntlet). One closure PDF, uploaded once, fans out to a whole
-- shift by reference.
CREATE TABLE media_assets (
    id           VARCHAR(64)  PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    blob_id      VARCHAR(64)  NOT NULL,       -- handle in knowledge_blobs / BlobStore
    filename     TEXT         NOT NULL,
    content_type VARCHAR(128) NOT NULL,       -- sniffed, authoritative
    size_bytes   BIGINT       NOT NULL,
    sha256       VARCHAR(64)  NOT NULL,
    caption      VARCHAR(300),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX media_assets_tenant_idx ON media_assets(tenant_id, created_at DESC);

-- Attachments delivered to a chat inbox row (signed short-lived URLs are minted
-- at drain, never stored). JSON array of {assetId, contentType, filename, sizeBytes}.
ALTER TABLE chat_inbox ADD COLUMN media_json JSONB NOT NULL DEFAULT '[]'::jsonb;
