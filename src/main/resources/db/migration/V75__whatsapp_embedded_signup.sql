-- V75: Meta Embedded Signup + per-tenant Twilio subaccounts for WhatsApp.
--
-- whatsapp-channel-design.md §7 shipped a platform-singleton Twilio account;
-- §7.3 reserved the seam for per-tenant credentials behind ProviderCredsResolver.
-- This migration lands that seam's data model in the Tech Provider shape:
-- each customer business/WABA gets ONE dedicated Twilio subaccount under the
-- platform's parent account. Tenants still never see or hold Twilio
-- credentials — the subaccount and its auth token are platform-managed and
-- encrypted at rest (JwtSecretCipher, ciphertext+iv columns, same shape as
-- tenant_jwt_secrets in V13).
--
-- whatsapp_messaging_accounts — one row per (tenant, WABA): the Twilio
-- subaccount that owns every sender registered under that WABA. UNIQUE(waba_id)
-- is the ownership constraint: a WABA can never be claimed by two tenants.
CREATE TABLE whatsapp_messaging_accounts (
    id                    VARCHAR(64)  PRIMARY KEY,            -- wma_...
    tenant_id             VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    waba_id               VARCHAR(64)  NOT NULL,
    -- Assigned once the Twilio subaccount exists; NULL while PROVISIONING.
    twilio_subaccount_sid VARCHAR(64),
    -- Subaccount auth token, AES-256-GCM via JwtSecretCipher. Signs the
    -- subaccount's inbound webhooks and authenticates its API calls.
    auth_token_ciphertext TEXT,
    auth_token_iv         VARCHAR(64),
    friendly_name         VARCHAR(128) NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'PROVISIONING',
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CHECK (status IN ('PROVISIONING','ACTIVE','SUSPENDED','DISCONNECTED')),
    CONSTRAINT whatsapp_messaging_accounts_waba_uniq       UNIQUE (waba_id),
    CONSTRAINT whatsapp_messaging_accounts_subaccount_uniq UNIQUE (twilio_subaccount_sid)
);
CREATE INDEX whatsapp_messaging_accounts_tenant_idx
    ON whatsapp_messaging_accounts(tenant_id, status);

-- whatsapp_onboarding_attempts — the durable idempotency ledger for the
-- embedded-signup flow. One row per (tenant, phone, WABA) — the
-- idempotency_key is derived from exactly that triple, so browser refreshes,
-- double-clicks, and Twilio timeouts land on the SAME row and resume its
-- state machine instead of provisioning duplicates. Retrying a FAILED
-- attempt resets the same row to RECEIVED.
CREATE TABLE whatsapp_onboarding_attempts (
    id                    VARCHAR(64)  PRIMARY KEY,            -- woa_...
    tenant_id             VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    phone_e164            VARCHAR(20)  NOT NULL,
    waba_id               VARCHAR(64)  NOT NULL,
    meta_phone_number_id  VARCHAR(64),
    sender_id             VARCHAR(64)  REFERENCES whatsapp_senders(id) ON DELETE SET NULL,
    messaging_account_id  VARCHAR(64)  REFERENCES whatsapp_messaging_accounts(id) ON DELETE SET NULL,
    state                 VARCHAR(32)  NOT NULL DEFAULT 'RECEIVED',
    idempotency_key       VARCHAR(160) NOT NULL,
    failure_code          VARCHAR(64),
    -- Sanitized before persist — safe to surface to the tenant operator.
    failure_message       TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at          TIMESTAMPTZ,
    CHECK (state IN ('RECEIVED','ACCOUNT_PROVISIONED','SENDER_SUBMITTED','COMPLETED','FAILED')),
    CONSTRAINT whatsapp_onboarding_attempts_idem_uniq UNIQUE (idempotency_key)
);
CREATE INDEX whatsapp_onboarding_attempts_tenant_idx
    ON whatsapp_onboarding_attempts(tenant_id, updated_at DESC);

-- Embedded-signup provenance on the sender row. NULL for legacy senders
-- registered under the platform-singleton account — the creds resolver
-- falls back to the platform credential for those, so existing senders
-- keep working unchanged.
ALTER TABLE whatsapp_senders
    ADD COLUMN waba_id              VARCHAR(64),
    ADD COLUMN meta_phone_number_id VARCHAR(64),
    ADD COLUMN messaging_account_id VARCHAR(64) REFERENCES whatsapp_messaging_accounts(id);
CREATE INDEX whatsapp_senders_messaging_account_idx
    ON whatsapp_senders(messaging_account_id)
    WHERE messaging_account_id IS NOT NULL;

-- A BSP sender SID identifies exactly one sender row. V25 omitted this;
-- the subaccount model makes the invariant load-bearing (inbound routing
-- and creds resolution both key off the sender row the SID maps to).
CREATE UNIQUE INDEX whatsapp_senders_twilio_sid_uniq
    ON whatsapp_senders(twilio_sender_sid)
    WHERE twilio_sender_sid IS NOT NULL;
