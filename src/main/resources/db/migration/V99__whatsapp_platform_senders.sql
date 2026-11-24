-- whatsapp-shared-sender-design.md §4.2 — platform senders: WhatsApp numbers
-- owned by the platform (no workspace) that platform admins assign to
-- workspaces. Assigned workspaces send on them; only their registered staff
-- reach an assistant through them.

-- Owner of a sender: a workspace (every sender until now) or the platform.
ALTER TABLE whatsapp_senders
    ADD COLUMN owner_kind     VARCHAR(16) NOT NULL DEFAULT 'WORKSPACE',
    ADD COLUMN unrouted_reply TEXT,
    ALTER COLUMN tenant_id DROP NOT NULL;
ALTER TABLE whatsapp_senders
    ADD CONSTRAINT whatsapp_senders_owner_chk CHECK (
        (owner_kind = 'WORKSPACE' AND tenant_id IS NOT NULL) OR
        (owner_kind = 'PLATFORM'  AND tenant_id IS NULL AND messaging_account_id IS NULL));

-- A workspace a platform sender is assigned to, with the business name the
-- platform shows recipients (§9.2) and the workspace's fair share (§9.4).
CREATE TABLE whatsapp_sender_assignments (
    id                 VARCHAR(40)  PRIMARY KEY,
    sender_id          VARCHAR(64)  NOT NULL REFERENCES whatsapp_senders(id),
    tenant_id          VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    label              VARCHAR(60)  NOT NULL,
    non_member_reply   TEXT,
    daily_outbound_cap INT          NOT NULL DEFAULT 1000,
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    assigned_by        VARCHAR(128) NOT NULL,
    assigned_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ended_by           VARCHAR(128),
    ended_at           TIMESTAMPTZ,
    CONSTRAINT whatsapp_sender_assignments_status_chk
        CHECK (status IN ('ACTIVE','PAUSED','ENDED')),
    CONSTRAINT whatsapp_sender_assignments_cap_chk
        CHECK (daily_outbound_cap >= 0)
);
CREATE UNIQUE INDEX whatsapp_sender_assignments_live_uniq
    ON whatsapp_sender_assignments(sender_id, tenant_id) WHERE status <> 'ENDED';
CREATE UNIQUE INDEX whatsapp_sender_assignments_label_uniq
    ON whatsapp_sender_assignments(sender_id, lower(label)) WHERE status <> 'ENDED';
CREATE INDEX whatsapp_sender_assignments_tenant_idx
    ON whatsapp_sender_assignments(tenant_id) WHERE status <> 'ENDED';

-- Who assigned, relabelled, paused or ended what. No FKs: it outlives both.
CREATE TABLE whatsapp_sender_assignment_events (
    id           BIGSERIAL    PRIMARY KEY,
    sender_id    VARCHAR(64)  NOT NULL,
    tenant_id    VARCHAR(64)  NOT NULL,
    action       VARCHAR(24)  NOT NULL,
    detail_json  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    actor        VARCHAR(128) NOT NULL,
    occurred_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX whatsapp_sender_assignment_events_sender_idx
    ON whatsapp_sender_assignment_events(sender_id, occurred_at DESC);

-- Phones an assigned workspace registered as its staff on a platform sender (§7.6).
CREATE TABLE whatsapp_sender_members (
    sender_id   VARCHAR(64)  NOT NULL REFERENCES whatsapp_senders(id) ON DELETE CASCADE,
    tenant_id   VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    phone_e164  VARCHAR(20)  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (sender_id, tenant_id, phone_e164)
);
CREATE INDEX whatsapp_sender_members_phone_idx
    ON whatsapp_sender_members(sender_id, phone_e164);

-- The router's per-phone state on a platform sender (§7.2): the pinned
-- workspace and an open «¿Con cuál negocio?» question.
CREATE TABLE whatsapp_shared_routes (
    sender_id         VARCHAR(64) NOT NULL REFERENCES whatsapp_senders(id) ON DELETE CASCADE,
    end_user_phone    VARCHAR(20) NOT NULL,
    active_tenant_id  VARCHAR(64),
    active_at         TIMESTAMPTZ,
    pending_choice    JSONB,
    PRIMARY KEY (sender_id, end_user_phone)
);

-- One open session per phone per number PER WORKSPACE: on a platform sender
-- each assigned workspace keeps its own conversation with the same phone.
DROP INDEX channel_sessions_open_unique_idx;
CREATE UNIQUE INDEX channel_sessions_open_unique_idx
    ON channel_sessions(tenant_id, sender_id, end_user_phone)
    WHERE status = 'OPEN';

-- Platform-sender inbound has no workspace until the router picks one (§7.4);
-- platform-originated replies (STOP confirmation, the pick question, the
-- unrouted reply) never get one.
ALTER TABLE inbound_messages
    ALTER COLUMN tenant_id DROP NOT NULL,
    ADD COLUMN route_reason VARCHAR(32);
ALTER TABLE outbound_messages
    ALTER COLUMN tenant_id DROP NOT NULL;
CREATE INDEX outbound_messages_tenant_sender_phone_idx
    ON outbound_messages(sender_id, end_user_phone, tenant_id, sent_at DESC);

-- A STOP on a platform sender counts against the workspace that wrote last (§9.4).
ALTER TABLE channel_opt_outs
    ADD COLUMN attributed_tenant_id VARCHAR(64);

-- Keyword scopes (§8.2). ROUTED prefixes stay unique on the number (today's
-- rule); REPLY prefixes are unique per workspace on the number.
ALTER TABLE whatsapp_keyword_actions
    ADD COLUMN scope VARCHAR(8) NOT NULL DEFAULT 'ROUTED';
ALTER TABLE whatsapp_keyword_actions
    ADD CONSTRAINT whatsapp_keyword_actions_scope_chk CHECK (scope IN ('ROUTED','REPLY'));
ALTER TABLE whatsapp_keyword_actions
    DROP CONSTRAINT whatsapp_keyword_actions_uniq;
CREATE UNIQUE INDEX whatsapp_keyword_actions_routed_uniq
    ON whatsapp_keyword_actions(sender_id, prefix) WHERE scope = 'ROUTED';
CREATE UNIQUE INDEX whatsapp_keyword_actions_reply_uniq
    ON whatsapp_keyword_actions(sender_id, tenant_id, prefix) WHERE scope = 'REPLY';

-- The placeholder the platform fills with the business name (§9.2).
ALTER TABLE whatsapp_templates
    ADD COLUMN business_label_var INT;

-- Transfers to or from the platform have no workspace on that side.
ALTER TABLE whatsapp_sender_transfers
    ALTER COLUMN source_tenant_id DROP NOT NULL,
    ALTER COLUMN dest_tenant_id   DROP NOT NULL;
