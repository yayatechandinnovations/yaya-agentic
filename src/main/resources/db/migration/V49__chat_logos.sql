-- Chat channel — platform-hosted entry-point logos (chat-channel-design.md §13:
-- "logos are validated and re-served from the platform blob store, never
-- hot-linked"). Operators upload an image; it is stored here and served from a
-- stable public URL that the entry point's logo_url points at.

CREATE TABLE chat_logos (
    id           VARCHAR(64)  PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    content_type VARCHAR(64)  NOT NULL,
    data         BYTEA        NOT NULL,
    byte_size    INT          NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX chat_logos_tenant_idx ON chat_logos(tenant_id);
