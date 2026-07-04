-- Phone-change ceremony state (chat-account-self-service-design.md §6.2, §9.2).
--
-- Changing the phone that anchors an account is a two-proof ceremony: an OTP to
-- the CURRENT number (the "right person" gate) then an OTP to the NEW number
-- (proves control of the destination). This table holds the short-lived state
-- between those proofs so the two HTTP calls can't be spoofed out of order.
CREATE TABLE chat_phone_changes (
    id              UUID PRIMARY KEY,
    account_id      UUID NOT NULL REFERENCES chat_accounts(id) ON DELETE CASCADE,
    old_phone       VARCHAR(20) NOT NULL,
    new_phone       VARCHAR(20) NOT NULL,
    state           VARCHAR(16) NOT NULL,     -- OLD_PENDING | NEW_PENDING | COMPLETE | ABORTED
    old_verified_at TIMESTAMPTZ,
    attempts        INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL
);

-- At most one in-flight change per account (a new start aborts the prior).
CREATE UNIQUE INDEX chat_phone_changes_active_uq
    ON chat_phone_changes (account_id) WHERE state IN ('OLD_PENDING', 'NEW_PENDING');
