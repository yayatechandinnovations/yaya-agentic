-- Attachments on the sessions API, codes read from images, voice metering
-- (assistant-channels-replies-media-voice-design.md §3–§4, tracker contract C).

-- The sessions API admits uploads by the session's owner (a verified subject),
-- not by a chat account: the per-user daily byte ceiling is keyed on it.
ALTER TABLE chat_attachments
    ADD COLUMN IF NOT EXISTS principal_subject VARCHAR(255);

CREATE INDEX IF NOT EXISTS chat_attachments_subject_created_idx
    ON chat_attachments (principal_subject, created_at);

-- QR / barcodes decoded from an image: [{"format": "QR_CODE", "text": "…"}].
ALTER TABLE chat_attachments
    ADD COLUMN IF NOT EXISTS codes_json JSONB;

-- Voice notes: the audio length, from the container or the transcriber.
ALTER TABLE chat_attachments
    ADD COLUMN IF NOT EXISTS duration_seconds INT;

-- Retention: a sent attachment keeps its row (the transcript still shows the
-- name) after its bytes are removed; blob_id NULL = the blob is gone.
ALTER TABLE chat_attachments
    ALTER COLUMN blob_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS chat_attachments_retention_idx
    ON chat_attachments (referenced_at)
    WHERE turn_id IS NOT NULL AND blob_id IS NOT NULL;

-- Media spend per month: seconds of audio transcribed and images given to the
-- model. Counted for every profile (the tenant's own spend), unlike messages,
-- which only metered profiles draw on.
ALTER TABLE tenant_usage_monthly
    ADD COLUMN IF NOT EXISTS voice_seconds BIGINT NOT NULL DEFAULT 0;

ALTER TABLE tenant_usage_monthly
    ADD COLUMN IF NOT EXISTS images_in BIGINT NOT NULL DEFAULT 0;

ALTER TABLE profile_usage_monthly
    ADD COLUMN IF NOT EXISTS voice_seconds BIGINT NOT NULL DEFAULT 0;

ALTER TABLE profile_usage_monthly
    ADD COLUMN IF NOT EXISTS images_in BIGINT NOT NULL DEFAULT 0;
