-- S5: shipping config, shipments + tracking, delivery slots, and the in-house
-- courier fleet (design §8.4–8.9, §9). Same discipline as the stock ledger:
-- tracking_events is append-only; slot capacity is guarded pessimistically.

-- ── shipping config (merchant-authored, §8.5) ────────────────────────────────

CREATE TABLE shipping_zones (
    id           VARCHAR(64)  PRIMARY KEY,
    store_id     VARCHAR(64)  NOT NULL REFERENCES stores (id),
    name         VARCHAR(200) NOT NULL,
    regions_json JSONB        NOT NULL      -- [{country, region?, postalPrefix?}] matchers
);
CREATE INDEX idx_shipping_zones_store ON shipping_zones (store_id);

CREATE TABLE delivery_methods (
    id       VARCHAR(64)  PRIMARY KEY,
    store_id VARCHAR(64)  NOT NULL REFERENCES stores (id),
    kind     VARCHAR(16)  NOT NULL,         -- SHIPPING | LOCAL_DELIVERY | PICKUP | DIGITAL
    label    VARCHAR(200) NOT NULL,
    active   BOOLEAN      NOT NULL DEFAULT TRUE
);
CREATE INDEX idx_delivery_methods_store ON delivery_methods (store_id);

CREATE TABLE carriers (
    id              VARCHAR(64)  PRIMARY KEY,
    store_id        VARCHAR(64)  NOT NULL REFERENCES stores (id),
    kind            VARCHAR(24)  NOT NULL,  -- MANUAL | IN_HOUSE | <integration, vNext>
    name            VARCHAR(200) NOT NULL,
    credentials_ref VARCHAR(200),
    config_json     JSONB                   -- e.g. { webhookSecret } for tracking webhooks
);
CREATE INDEX idx_carriers_store ON carriers (store_id);

CREATE TABLE shipping_rates (
    id                 VARCHAR(64) PRIMARY KEY,
    store_id           VARCHAR(64) NOT NULL,
    zone_id            VARCHAR(64) NOT NULL REFERENCES shipping_zones (id),
    method_id          VARCHAR(64) NOT NULL REFERENCES delivery_methods (id),
    carrier_id         VARCHAR(64),
    service_level      VARCHAR(100),
    rule_json          JSONB       NOT NULL,   -- Flat | ByWeight | ByPrice | FreeOver (CarrierCalculated = vNext, rejected)
    handling_fee_minor BIGINT      NOT NULL DEFAULT 0,
    transit_json       JSONB                   -- { minDays, maxDays } → the promised window (§8.4)
);
CREATE INDEX idx_shipping_rates_zone ON shipping_rates (zone_id, method_id);

-- Delivery/pickup slots (§8.7, decision 10): hard capacity holds, booked in the
-- checkout saga under the same pessimistic guard as stock (§14 no slot oversell).
CREATE TABLE delivery_slots (
    id          VARCHAR(64) PRIMARY KEY,
    store_id    VARCHAR(64) NOT NULL,
    location_id VARCHAR(64) NOT NULL,
    method_id   VARCHAR(64) NOT NULL REFERENCES delivery_methods (id),
    starts_at   TIMESTAMPTZ NOT NULL,
    ends_at     TIMESTAMPTZ NOT NULL,
    capacity    INT         NOT NULL CHECK (capacity > 0),
    booked      INT         NOT NULL DEFAULT 0,
    CONSTRAINT chk_slot_not_oversold CHECK (booked <= capacity)
);
CREATE INDEX idx_delivery_slots_lookup ON delivery_slots (store_id, method_id, location_id, starts_at);

-- Which order holds which slot — makes book/release idempotent per order
-- (a retried checkout re-attaches; a cancel releases exactly once).
CREATE TABLE delivery_slot_bookings (
    slot_id   VARCHAR(64) NOT NULL REFERENCES delivery_slots (id),
    order_id  VARCHAR(64) NOT NULL,
    booked_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (slot_id, order_id)
);

-- ── fulfillment & delivery (§8.6–8.7) ────────────────────────────────────────

CREATE TABLE shipments (
    id              VARCHAR(64)  PRIMARY KEY,
    order_id        VARCHAR(64)  NOT NULL REFERENCES orders (id),
    store_id        VARCHAR(64)  NOT NULL,
    location_id     VARCHAR(64)  NOT NULL,
    method_id       VARCHAR(64),
    method_kind     VARCHAR(16)  NOT NULL,      -- SHIPPING | LOCAL_DELIVERY | PICKUP
    carrier_id      VARCHAR(64),
    service_level   VARCHAR(100),
    lines_json      JSONB        NOT NULL,      -- [{sku, qty}] — full order lines (v1 splits at line granularity)
    parcel_json     JSONB,                      -- { weightGrams, dims } captured at pack
    status          VARCHAR(16)  NOT NULL,      -- DRAFT → PICKING → PACKED → DISPATCHED
    delivery_status VARCHAR(24),                -- §8.7 DeliveryStatus, set from dispatch on
    tracking_number VARCHAR(200),
    tracking_url    VARCHAR(500),
    label_ref       VARCHAR(200),
    slot_id         VARCHAR(64),
    promised_from   TIMESTAMPTZ,
    promised_to     TIMESTAMPTZ,
    pickup_otp      VARCHAR(16),                -- PICKUP: shown at the counter (§8.7)
    proof_json      JSONB,                      -- signature | photo | otp | geo
    dispatched_at   TIMESTAMPTZ,
    delivered_at    TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_shipments_order ON shipments (order_id);
CREATE INDEX idx_shipments_tracking ON shipments (carrier_id, tracking_number);

-- Append-only, like stock_movements: "where was my order on Tuesday?" is a row.
CREATE TABLE tracking_events (
    id          VARCHAR(64)  PRIMARY KEY,
    shipment_id VARCHAR(64)  NOT NULL REFERENCES shipments (id),
    status      VARCHAR(24)  NOT NULL,
    description VARCHAR(500),
    location    VARCHAR(200),
    occurred_at TIMESTAMPTZ  NOT NULL,
    recorded_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_tracking_events_shipment ON tracking_events (shipment_id, occurred_at);

CREATE OR REPLACE FUNCTION yaya_sales.tracking_events_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'tracking_events is append-only — a correction is a new event';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_tracking_events_immutable
    BEFORE UPDATE OR DELETE ON tracking_events
    FOR EACH ROW EXECUTE FUNCTION yaya_sales.tracking_events_immutable();

-- ── in-house delivery fleet (§8.9: self-registered, KYC-reviewed) ───────────

-- The invite gate: only couriers the merchant invited can register. Single-use,
-- expiring; the token itself is never stored — only its SHA-256.
CREATE TABLE delivery_agent_invites (
    token_hash VARCHAR(64)  PRIMARY KEY,
    store_id   VARCHAR(64)  NOT NULL REFERENCES stores (id),
    created_by VARCHAR(200) NOT NULL,
    expires_at TIMESTAMPTZ  NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_agent_invites_store ON delivery_agent_invites (store_id);

-- credential_ref = SHA-256 of the issued bearer token (never the token itself):
-- a registration-scoped token at phone verification, ROTATED to the operational
-- courier credential at approval (§10.4, §15).
CREATE TABLE delivery_agents (
    id                    VARCHAR(64)  PRIMARY KEY,
    store_id              VARCHAR(64)  NOT NULL REFERENCES stores (id),
    legal_name            VARCHAR(300) NOT NULL,
    legal_address_json    JSONB        NOT NULL,
    phone                 VARCHAR(40)  NOT NULL,
    phone_verified        BOOLEAN      NOT NULL DEFAULT FALSE,
    phone_otp_hash        VARCHAR(64),
    phone_otp_expires_at  TIMESTAMPTZ,
    id_doc_json           JSONB,                    -- { type, number } (scans live in documents)
    vehicle_json          JSONB        NOT NULL,    -- { kind, make, model, plate }
    status                VARCHAR(16)  NOT NULL,    -- PENDING | DOCS_SUBMITTED | UNDER_REVIEW | APPROVED | REJECTED | SUSPENDED
    serves_from_json      JSONB,
    coverage_json         JSONB,
    credential_ref        VARCHAR(64) UNIQUE,
    registered_at         TIMESTAMPTZ  NOT NULL,
    approved_at           TIMESTAMPTZ,
    reviewed_by           VARCHAR(200),
    reject_reason         VARCHAR(500)
);
CREATE INDEX idx_delivery_agents_store ON delivery_agents (store_id, status);
CREATE INDEX idx_delivery_agents_phone ON delivery_agents (phone);

-- KYC document scans: PII, AES-256-GCM encrypted at rest (§15), decrypted only
-- on the operator's explicit review request — never returned raw in listings.
CREATE TABLE delivery_agent_documents (
    id          VARCHAR(64) PRIMARY KEY,
    agent_id    VARCHAR(64) NOT NULL REFERENCES delivery_agents (id),
    kind        VARCHAR(24) NOT NULL,   -- PHOTO_ID_FRONT | PHOTO_ID_BACK | DRIVER_LICENSE | VEHICLE_REGISTRATION | INSURANCE | OTHER
    blob_ref    TEXT        NOT NULL,   -- { ciphertext, iv } envelope
    status      VARCHAR(16) NOT NULL,   -- SUBMITTED | ACCEPTED | REJECTED
    uploaded_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_agent_documents_agent ON delivery_agent_documents (agent_id);

CREATE TABLE delivery_assignments (
    id          VARCHAR(64) PRIMARY KEY,
    shipment_id VARCHAR(64) NOT NULL REFERENCES shipments (id),
    agent_id    VARCHAR(64) NOT NULL REFERENCES delivery_agents (id),
    store_id    VARCHAR(64) NOT NULL,
    status      VARCHAR(16) NOT NULL,   -- ASSIGNED | ACCEPTED | COMPLETED | REASSIGNED
    assigned_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_assignments_agent ON delivery_assignments (agent_id, status);
CREATE INDEX idx_assignments_shipment ON delivery_assignments (shipment_id);

-- ── orders: the delivery snapshot (§8.2 — method + promised window + ship-to) ─

ALTER TABLE orders ADD COLUMN delivery_json JSONB;
