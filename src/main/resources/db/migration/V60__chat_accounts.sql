-- Chat accounts (chat-channel-design.md §20). Until now the verify flow was
-- stateless: a successful OTP minted a device pass and nothing was persisted.
-- To let a returning phone *re-authenticate* without re-entering its name — and
-- to know whether to ask for a name at all — we now persist one row per verified
-- phone. Keyed by the E.164 phone; tenant-agnostic, exactly like the device pass
-- it backs (the pass is platform-signed and crosses tenants).
--
-- A row is written only AFTER a successful verification, never on `verify/start`,
-- so existence tracks real completed registrations — not merely who was sent an
-- SMS.
CREATE TABLE chat_accounts (
    phone_e164  VARCHAR(20)   PRIMARY KEY,
    name        VARCHAR(120),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
