-- Resolver-driven profile override guardrails
-- (docs/design/resolver-profile-override-design.md §7, §10).
--
-- A channel AuthResolver may now return a structured profile selection that
-- overrides the surface's bound profile. These per-surface columns bound that
-- power:
--   allowed_override_profiles_json  JSON array of profile keys the surface
--       permits an override into. '[]' (default) = any published in-tenant
--       profile is allowed (the resolver is tenant-trusted). A non-empty set
--       restricts overrides to those keys — for public surfaces that want a
--       buggy/compromised resolver kept out of privileged internal profiles.
--   on_override_denied  what to do when an override fails a guardrail:
--       'IGNORE' (default) runs the bound profile; 'REJECT' drops the inbound.
--
-- Both default to today's behavior, so existing rows are unaffected: no
-- allowlist + IGNORE == "honor any published override, else fall back."

ALTER TABLE channel_bindings
    ADD COLUMN allowed_override_profiles_json jsonb        NOT NULL DEFAULT '[]',
    ADD COLUMN on_override_denied            varchar(8)   NOT NULL DEFAULT 'IGNORE';

ALTER TABLE chat_entry_points
    ADD COLUMN allowed_override_profiles_json jsonb        NOT NULL DEFAULT '[]',
    ADD COLUMN on_override_denied            varchar(8)   NOT NULL DEFAULT 'IGNORE';
