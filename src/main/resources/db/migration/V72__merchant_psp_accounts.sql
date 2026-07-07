-- Merchant PSP onboarding (merchant-psp-onboarding-design.md §5): the connected
-- account a store owner obtains by clicking "Connect PayPal/Stripe" in the app.
-- Per (tenant, provider) — an operator connects once and all their stores reuse
-- it. The checkout providers already consume account_id (Stripe-Account header /
-- PayPal payee.merchant_id); this table is what finally produces it.
CREATE TABLE merchant_psp_accounts (
    tenant_id       VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    provider        VARCHAR(16)  NOT NULL,           -- 'paypal' | 'stripe'
    account_id      VARCHAR(255),                    -- merchantIdInPayPal / acct_… (null until returned)
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    charges_enabled BOOLEAN      NOT NULL DEFAULT false,
    tracking_ref    VARCHAR(128),                    -- our id passed to the PSP for async correlation
    detail_json     JSONB        NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, provider),
    CONSTRAINT merchant_psp_accounts_status_chk
        CHECK (status IN ('PENDING','ONBOARDING','ACTIVE','RESTRICTED','DISABLED'))
);

-- The PSP webhook/return carries our tracking_ref (not the tenant), so we look
-- the row up by it to reconcile async onboarding completion.
CREATE INDEX merchant_psp_accounts_tracking_ix ON merchant_psp_accounts (tracking_ref);
