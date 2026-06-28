-- S1 — first of the SMS-channel migrations. Lands the A2P 10DLC compliance
-- aggregates ONLY: the tenant Brand (registered business identity with the
-- carriers) and its Campaigns (registered use cases). SMS senders (the phone
-- numbers) arrive in V44, the inbound/outbound deltas later — same
-- one-migration-per-milestone discipline as the WhatsApp V25..V29 sequence.
--
-- See `sms-channel-design.md` §6 (the A2P subsystem) and §8 (data model).
--
-- Why this is the genuinely-new slice: WhatsApp never needed carrier Brand /
-- Campaign registration because Meta's business verification plays that role.
-- US SMS over a 10-digit long code is filtered/blocked until the number's
-- Campaign is APPROVED, so the compliance state machines below gate sending.
--
-- Both tables are tenant-scoped (VARCHAR(64) -> tenants(id)) exactly like the
-- WhatsApp channel tables; they migrate alongside the rest when the tenant
-- UUID PK cutover eventually ships.

CREATE TABLE sms_brands (
    id                       VARCHAR(64)  PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    business_json            JSONB        NOT NULL,
    status                   VARCHAR(24)  NOT NULL,
    twilio_profile_sid       VARCHAR(64),                       -- BU... Secondary Customer Profile
    tcr_brand_id             VARCHAR(64),                       -- assigned by TCR via Twilio
    last_failure_code        VARCHAR(64),
    last_failure_message     TEXT,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_sync_at             TIMESTAMPTZ,
    CONSTRAINT sms_brands_status_chk
        CHECK (status IN ('DRAFT','SUBMITTING','PENDING_VERIFICATION','VERIFIED','FAILED'))
);

-- v1 enforces one Brand per tenant in the service layer; the table admits N so
-- the multi-brand seam (`sms-channel-design.md` §4.2) needs no schema change.
CREATE INDEX sms_brands_tenant_idx ON sms_brands(tenant_id, status);

CREATE TABLE sms_campaigns (
    id                       VARCHAR(64)  PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    brand_id                 VARCHAR(64)  NOT NULL REFERENCES sms_brands(id) ON DELETE CASCADE,
    use_case                 VARCHAR(40)  NOT NULL,
    description              TEXT         NOT NULL,
    sample_messages_json     JSONB        NOT NULL,             -- 2..5 strings
    opt_in_json              JSONB        NOT NULL,
    evidence_json            JSONB,                             -- restricted use cases only (§6.3)
    requires_operator_review BOOLEAN      NOT NULL DEFAULT FALSE,
    status                   VARCHAR(24)  NOT NULL,
    twilio_campaign_sid      VARCHAR(64),
    messaging_service_sid    VARCHAR(64),                       -- the MS senders attach to
    last_failure_code        VARCHAR(64),
    last_failure_message     TEXT,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_sync_at             TIMESTAMPTZ,
    CONSTRAINT sms_campaigns_status_chk
        CHECK (status IN ('DRAFT','SUBMITTING','PENDING_REVIEW','APPROVED','SUSPENDED','FAILED'))
);

CREATE INDEX sms_campaigns_tenant_idx ON sms_campaigns(tenant_id, status);
CREATE INDEX sms_campaigns_brand_idx  ON sms_campaigns(brand_id);
