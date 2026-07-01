-- Managed storage for uploaded knowledge files
-- (knowledge-file-upload-design.md §6). Operators upload documents through the
-- admin console; the bytes land here and are referenced by SourceLocation.Uploaded
-- via an opaque, tenant-scoped blob_id. Physical location is invisible to the user.
--
-- `data` holds the bytes when blob-store=db (this increment). When blob-store=minio
-- lands, `data` is NULL and `storage_ref` carries the object key — same table, so
-- the reference from a knowledge source never has to change.

CREATE TABLE knowledge_blobs (
    tenant_id     VARCHAR(64)  NOT NULL,
    blob_id       VARCHAR(64)  NOT NULL,
    filename      TEXT         NOT NULL,
    content_type  VARCHAR(128) NOT NULL,          -- sniffed server-side, authoritative
    size_bytes    BIGINT       NOT NULL,
    sha256        CHAR(64)     NOT NULL,
    data          BYTEA,                            -- populated only when blob-store=db
    storage_ref   TEXT,                             -- object key when blob-store=minio
    scan_status   VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING | CLEAN | INFECTED
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, blob_id)
);

-- Within-tenant dedup: an upload whose sha256 already exists returns the
-- existing blob_id instead of storing a second copy (design §11, decision #3).
CREATE INDEX ix_knowledge_blobs_sha ON knowledge_blobs (tenant_id, sha256);
