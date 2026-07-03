-- Session Start Flows (docs/design/session-start-flows.md §5, §6, §10).
--
-- A Start Flow is tenant-scoped, versioned config — an ordered list of steps
-- run deterministically at session start (before the LLM) to recognize /
-- verify / register / enrich, and to shape the opening greeting from the
-- results. A session pins (id, version) at start(), exactly like a profile.
--
-- steps_json    : List<StartStep>   (sealed: RunTool | Decision; "kind" discriminator)
-- greeting_json : GreetingPolicy    (mode + per-outcome templates + optional LlmOpening)
-- policy_json   : FlowPolicy        (budget, maxSteps, runScope, allowWhenAnonymous)

CREATE TABLE session_start_flows (
    tenant_id     varchar(64)  NOT NULL,
    id            varchar(128) NOT NULL,
    version       int          NOT NULL,
    name          text         NOT NULL,
    description   text,
    steps_json    jsonb        NOT NULL DEFAULT '[]',
    greeting_json jsonb        NOT NULL DEFAULT '{}',
    policy_json   jsonb        NOT NULL DEFAULT '{}',
    status        varchar(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id, version),
    CONSTRAINT session_start_flows_status_chk
        CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'))
);

-- Channel + profile bindings. Nullable: null == no flow (today's behavior).
-- Channel binding wins over the profile default; resolved once at start().
ALTER TABLE whatsapp_senders  ADD COLUMN start_flow_id         varchar(128);
ALTER TABLE chat_entry_points ADD COLUMN start_flow_id         varchar(128);
ALTER TABLE profiles          ADD COLUMN default_start_flow_id varchar(128);
