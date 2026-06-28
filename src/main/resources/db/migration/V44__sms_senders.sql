-- S2 — the SMS sender aggregate: a registered phone-number endpoint, sibling
-- to whatsapp_senders (V25) and reusing the same 7-state sender machine. A US
-- sender attaches to an APPROVED A2P campaign (V43); a DR sender carries a
-- regulatory bundle instead. See `sms-channel-design.md` §8 (data model),
-- §7 (acquisition + market), §5.2 (the campaign-APPROVED gate on -> ACTIVE).
--
-- Deviation from the §8 DDL, on purpose: the design's CHECK required the
-- market-appropriate id to be NOT NULL. That can't hold at DRAFT (a DR bundle
-- is attached during onboarding, not at row creation), so the DB enforces only
-- *exclusivity* — a sender is never both campaign- and bundle-backed — and the
-- service enforces "US needs a campaign / DO needs a bundle" at submit time,
-- where the lifecycle actually requires it.

CREATE TABLE sms_senders (
    id                       VARCHAR(64)  PRIMARY KEY,
    tenant_id                VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    phone_e164               VARCHAR(20)  NOT NULL,
    market                   VARCHAR(4)   NOT NULL,
    sender_kind              VARCHAR(16)  NOT NULL DEFAULT 'LONG_CODE',
    acquisition              VARCHAR(12)  NOT NULL DEFAULT 'PURCHASED',
    campaign_id              VARCHAR(64)  REFERENCES sms_campaigns(id) ON DELETE SET NULL,
    regulatory_bundle_sid    VARCHAR(64),
    status                   VARCHAR(20)  NOT NULL,
    twilio_sender_sid        VARCHAR(64),
    onboarding_state_json    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    webhook_secret_enc       BYTEA        NOT NULL,
    default_locale           VARCHAR(16)  NOT NULL DEFAULT 'en-US',
    metadata_json            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_sync_at             TIMESTAMPTZ,
    CONSTRAINT sms_senders_phone_uniq UNIQUE (phone_e164),
    CONSTRAINT sms_senders_market_chk      CHECK (market IN ('US','DO')),
    CONSTRAINT sms_senders_kind_chk        CHECK (sender_kind IN ('LONG_CODE','SHORT_CODE','TOLL_FREE')),
    CONSTRAINT sms_senders_acquisition_chk CHECK (acquisition IN ('PURCHASED','HOSTED')),
    CONSTRAINT sms_senders_status_chk
        CHECK (status IN ('DRAFT','SUBMITTING','PENDING_REVIEW','ACTIVE',
                          'SUSPENDED','FAILED','REVOKED')),
    -- a sender is never both campaign- and bundle-backed (exclusivity only —
    -- requiredness is enforced at submit by the service; see the header note)
    CONSTRAINT sms_senders_compliance_path_chk
        CHECK (NOT (campaign_id IS NOT NULL AND regulatory_bundle_sid IS NOT NULL))
);

CREATE INDEX sms_senders_tenant_idx   ON sms_senders(tenant_id, status);
CREATE INDEX sms_senders_campaign_idx ON sms_senders(campaign_id);
