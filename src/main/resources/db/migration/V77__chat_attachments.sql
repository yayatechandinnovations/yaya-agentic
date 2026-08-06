-- Outbound chat media (chat-experience-design.md §6): photos, voice notes and
-- documents a verified user sends into a chat. Bytes live in the BlobStore;
-- this table is the logical, session-scoped handle. Upload-then-reference:
-- an attachment is created unreferenced, then stamped with the turn that used
-- it; rows never referenced are garbage-collected after a grace window.

CREATE TABLE chat_attachments (
    id             VARCHAR(44)  PRIMARY KEY,          -- chatatt_<uuid>
    tenant_id      VARCHAR(64)  NOT NULL,
    session_id     VARCHAR(64)  NOT NULL,
    account_id     UUID,                              -- uploader (L1 floor)
    blob_id        VARCHAR(128) NOT NULL,
    filename       VARCHAR(255) NOT NULL,
    content_type   VARCHAR(128) NOT NULL,             -- sniffed, never declared
    size_bytes     BIGINT       NOT NULL,
    sha256         VARCHAR(64)  NOT NULL,
    kind           VARCHAR(10)  NOT NULL,             -- IMAGE | AUDIO | DOCUMENT
    transcript     TEXT,                              -- voice notes (Whisper)
    extracted_text TEXT,                              -- documents (Tika, bounded)
    turn_id        VARCHAR(64),                       -- set when a message references it
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    referenced_at  TIMESTAMPTZ
);

CREATE INDEX chat_attachments_session_idx ON chat_attachments (session_id);
-- The orphan sweep scans only unreferenced rows.
CREATE INDEX chat_attachments_orphan_idx ON chat_attachments (created_at)
    WHERE turn_id IS NULL;
-- The per-account daily byte ceiling sums recent uploads.
CREATE INDEX chat_attachments_account_created_idx ON chat_attachments (account_id, created_at);
