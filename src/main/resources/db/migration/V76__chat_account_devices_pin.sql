-- Device PIN unlock & per-device sign-out (chat-experience-design.md §5).
--
-- Until now the device pass was simultaneously the refresh credential and the
-- session credential: one 30-day bearer, revocable only account-wide via
-- pass_gen. This migration splits the model: each activated device gets a row
-- holding a hashed per-device secret (proof of "activated device"), and the
-- account gets an optional PIN (proof of "right person"). Everyday login
-- becomes pin-unlock(deviceSecret, pin) -> short-lived pass; OTP is reserved
-- for enrollment, new devices, and PIN recovery.

ALTER TABLE chat_accounts
    ADD COLUMN pin_hash            TEXT,
    ADD COLUMN pin_set_at          TIMESTAMPTZ,
    ADD COLUMN pin_failed_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN pin_locked_until    TIMESTAMPTZ;

CREATE TABLE chat_account_devices (
    account_id         UUID         NOT NULL REFERENCES chat_accounts (id) ON DELETE CASCADE,
    device_id          VARCHAR(64)  NOT NULL,
    device_secret_hash VARCHAR(64)  NOT NULL,
    label              VARCHAR(120),
    -- Per-device policy: the account-level PIN is asked on this device only
    -- while this flag holds ("don't ask on this phone" turns it off).
    pin_required       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at         TIMESTAMPTZ,
    PRIMARY KEY (account_id, device_id)
);

-- pin-unlock arrives with only (device_id, secret) — no account id — so the
-- lookup fans out over rows sharing the client-generated device_id and picks
-- the one whose secret hash matches.
CREATE INDEX chat_account_devices_device_id_idx ON chat_account_devices (device_id);
