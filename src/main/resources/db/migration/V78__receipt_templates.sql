-- Receipt / ticket previews (chat-experience-design.md §7).
--
-- receipt_templates: tenant-authored declarative layouts over the generic,
-- domain-free element vocabulary (§7.4). The framework stores and serves them;
-- it never interprets the domain meaning ("lottery" exists only in tenant data).
--
-- chat_receipts: the wallet (§7.5) — one row per issued receipt instance for a
-- verified account. The template is SNAPSHOTTED at issuance (resolved decision:
-- receipts are documents; documents don't retro-edit when a template changes).

CREATE TABLE receipt_templates (
    id            VARCHAR(64)  PRIMARY KEY,           -- rt_<uuid>
    tenant_id     VARCHAR(64)  NOT NULL,
    name          VARCHAR(120) NOT NULL,
    kind_label    VARCHAR(60),                        -- operator-facing label only
    paper_cols    INT          NOT NULL DEFAULT 32,
    paper_profile VARCHAR(8)   NOT NULL DEFAULT '58mm',
    elements      JSONB        NOT NULL,
    brand         JSONB,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX receipt_templates_tenant_idx ON receipt_templates (tenant_id);

CREATE TABLE chat_receipts (
    id                VARCHAR(64) PRIMARY KEY,        -- rcpt_<uuid>
    tenant_id         VARCHAR(64) NOT NULL,
    account_id        UUID        NOT NULL,
    session_id        VARCHAR(64) NOT NULL,
    handle            VARCHAR(64),
    template_id       VARCHAR(64),
    template_snapshot JSONB       NOT NULL,
    data              JSONB       NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX chat_receipts_account_idx ON chat_receipts (account_id, created_at DESC);
