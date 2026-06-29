-- Chat channel — per-entry-point enforcement of phone-verified device identity
-- (chat-channel-design.md §20.5). When true, a session can only start from a
-- device that holds a valid device pass (a platform-verified phone); the app's
-- app-wide first-run verification is the default source of that pass.

ALTER TABLE chat_entry_points
    ADD COLUMN requires_verified_phone BOOLEAN NOT NULL DEFAULT FALSE;
