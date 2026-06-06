-- Core schema for M1. Translated from design §8.
-- Knowledge / pgvector tables exist as stubs here but the vector column is
-- only populated starting in M2.5; the extension is enabled now so M1's
-- profile_knowledge_bindings + access checks have the rows they reference.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Tenants ---------------------------------------------------------------------

CREATE TABLE tenants (
    id            VARCHAR(64) PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    settings_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Personality -----------------------------------------------------------------

CREATE TABLE personality_fragments (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    locale      VARCHAR(16) NOT NULL DEFAULT 'en',
    voice_tone  TEXT        NOT NULL,
    rules_json  JSONB       NOT NULL DEFAULT '[]'::jsonb,
    refusals_json JSONB     NOT NULL DEFAULT '{}'::jsonb,
    version     INTEGER     NOT NULL DEFAULT 1,
    UNIQUE (tenant_id, locale, version)
);

-- Tools registry --------------------------------------------------------------

CREATE TABLE tools_registry (
    id                       VARCHAR(128) NOT NULL,
    tenant_id                VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    version                  INTEGER      NOT NULL DEFAULT 1,
    input_schema_json        JSONB        NOT NULL,
    output_schema_json       JSONB        NOT NULL,
    requires_json            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    handler_kind             VARCHAR(16)  NOT NULL,  -- 'BEAN' | 'HTTP'
    handler_bean_name        VARCHAR(255),
    handler_http_spec_json   JSONB,
    policy_json              JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status                   VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, id, version),
    CHECK (handler_kind IN ('BEAN','HTTP')),
    CHECK ((handler_kind = 'BEAN' AND handler_bean_name IS NOT NULL)
        OR (handler_kind = 'HTTP' AND handler_http_spec_json IS NOT NULL))
);

-- Capabilities ----------------------------------------------------------------

CREATE TABLE capabilities (
    id              VARCHAR(128) NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    version         INTEGER      NOT NULL DEFAULT 1,
    label           VARCHAR(255) NOT NULL,
    description     TEXT,
    llm_guidance    TEXT,
    tool_ids_json   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    requires_json   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, id, version)
);

-- Knowledge sources / documents / chunks (M1 stubs, populated in M2.5) --------

CREATE TABLE knowledge_sources (
    id                          VARCHAR(128) NOT NULL,
    tenant_id                   VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    version                     INTEGER      NOT NULL DEFAULT 1,
    name                        VARCHAR(255) NOT NULL,
    location_kind               VARCHAR(32)  NOT NULL,
    location_json               JSONB        NOT NULL,
    ingestion_policy_json       JSONB        NOT NULL,
    retrieval_policy_json       JSONB        NOT NULL,
    access_requirement_json     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status                      VARCHAR(16)  NOT NULL DEFAULT 'UNINDEXED',
    last_indexed_at             TIMESTAMPTZ,
    doc_count                   INTEGER      NOT NULL DEFAULT 0,
    chunk_count                 INTEGER      NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, id, version)
);

CREATE TABLE knowledge_documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id       VARCHAR(128) NOT NULL,
    source_version  INTEGER      NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    uri             TEXT,
    title           TEXT,
    content_hash    VARCHAR(128),
    metadata_json   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    last_modified   TIMESTAMPTZ,
    indexed_at      TIMESTAMPTZ,
    FOREIGN KEY (tenant_id, source_id, source_version)
        REFERENCES knowledge_sources(tenant_id, id, version) ON DELETE CASCADE
);

CREATE TABLE knowledge_chunks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id       VARCHAR(128) NOT NULL,
    source_version  INTEGER      NOT NULL,
    document_id     UUID         NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    tenant_id       VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    chunk_index     INTEGER      NOT NULL,
    text            TEXT         NOT NULL,
    embedding       vector(1536),
    metadata_json   JSONB        NOT NULL DEFAULT '{}'::jsonb,
    tsv             TSVECTOR
);
CREATE INDEX knowledge_chunks_tsv_idx ON knowledge_chunks USING GIN (tsv);

-- Auth bindings ---------------------------------------------------------------

CREATE TABLE auth_bindings (
    id                       VARCHAR(128) NOT NULL,
    tenant_id                VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    authenticator_ref        VARCHAR(128) NOT NULL,
    authorizer_chain_json    JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, id)
);

-- Profiles --------------------------------------------------------------------

CREATE TABLE profiles (
    id                 VARCHAR(128) NOT NULL,
    tenant_id          VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    version            INTEGER      NOT NULL DEFAULT 1,
    display_name       VARCHAR(255) NOT NULL,
    intro              TEXT         NOT NULL,
    system_prompt      TEXT         NOT NULL,
    capabilities_json  JSONB        NOT NULL DEFAULT '[]'::jsonb,
    auth_binding_id    VARCHAR(128),
    metadata_json      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deprecated_at      TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, id, version)
);

CREATE TABLE profile_knowledge_bindings (
    tenant_id        VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    profile_id       VARCHAR(128) NOT NULL,
    profile_version  INTEGER      NOT NULL,
    source_id        VARCHAR(128) NOT NULL,
    source_version   INTEGER      NOT NULL,
    PRIMARY KEY (tenant_id, profile_id, profile_version, source_id, source_version),
    FOREIGN KEY (tenant_id, profile_id, profile_version)
        REFERENCES profiles(tenant_id, id, version) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, source_id, source_version)
        REFERENCES knowledge_sources(tenant_id, id, version) ON DELETE CASCADE
);

-- Sessions & turns ------------------------------------------------------------

CREATE TABLE sessions (
    id                   UUID PRIMARY KEY,
    tenant_id            VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    principal_subject    VARCHAR(255),
    profile_id           VARCHAR(128) NOT NULL,
    profile_version      INTEGER      NOT NULL,
    channel              VARCHAR(64),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ended_at             TIMESTAMPTZ,
    FOREIGN KEY (tenant_id, profile_id, profile_version)
        REFERENCES profiles(tenant_id, id, version)
);
CREATE INDEX sessions_tenant_principal_idx ON sessions (tenant_id, principal_subject);

CREATE TABLE turns (
    id                 UUID PRIMARY KEY,
    session_id         UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    idx                INTEGER NOT NULL,
    role               VARCHAR(16) NOT NULL,
    content_json       JSONB NOT NULL,
    tool_calls_json    JSONB NOT NULL DEFAULT '[]'::jsonb,
    tool_results_json  JSONB NOT NULL DEFAULT '[]'::jsonb,
    retrieved_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    model              VARCHAR(128),
    tokens_in          INTEGER,
    tokens_out         INTEGER,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (session_id, idx)
);
CREATE INDEX turns_session_idx ON turns (session_id, idx);

CREATE TABLE intent_frames (
    session_id        UUID PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,
    frame_json        JSONB NOT NULL,
    parked_stack_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- AuthZ audit -----------------------------------------------------------------

CREATE TABLE audit_authz (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          VARCHAR(64) NOT NULL,
    session_id         UUID,
    turn_id            UUID,
    principal_subject  VARCHAR(255),
    tool_id            VARCHAR(128),
    args_json          JSONB,
    decision           VARCHAR(8) NOT NULL,    -- ALLOW | DENY
    user_reason        TEXT,
    audit_reason       TEXT,
    policy_trace_json  JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (decision IN ('ALLOW','DENY'))
);
CREATE INDEX audit_authz_tenant_time_idx ON audit_authz (tenant_id, created_at DESC);
