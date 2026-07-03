-- Durable cursor for a paused interactive Session Start Flow
-- (docs/design/session-start-flows.md §7.3, §10). One row per session, written
-- when the flow pauses on an Elicit and deleted on completion/abort, so a
-- multi-turn verification (e.g. phone-first OTP) survives across inbound
-- messages and process restarts.
--
-- scratch_json      : flow-private step vars (may hold short-lived secrets)
-- step_outputs_json : raw tool results keyed by step key, for StepOutput bindings

CREATE TABLE session_start_state (
    session_id        varchar(64)  PRIMARY KEY,
    flow_id           varchar(128) NOT NULL,
    flow_version      int          NOT NULL,
    current_step_key  varchar(128) NOT NULL,
    scratch_json      jsonb        NOT NULL DEFAULT '{}',
    step_outputs_json jsonb        NOT NULL DEFAULT '{}',
    updated_at        timestamptz  NOT NULL DEFAULT now()
);
