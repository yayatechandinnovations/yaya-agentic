-- Server-side chat directory + session linkage (chat-account-self-service-design.md §5.3, §7.1, §9.3).
--
-- Two jobs:
--  1. chat_account_tiles mirrors the user's added-chats list server-side, keyed
--     to the account, so a re-verified or reinstalled device recovers it.
--  2. sessions gains chat_account_id + entry_point_id so a conversation can be
--     found and resumed by account (surviving app restart, reinstall, and phone
--     change — the join is the account id, never the mutable phone string).
CREATE TABLE chat_account_tiles (
    account_id     UUID NOT NULL REFERENCES chat_accounts(id) ON DELETE CASCADE,
    entry_point_id VARCHAR(64) NOT NULL REFERENCES chat_entry_points(id) ON DELETE CASCADE,
    handle         VARCHAR(64),
    added_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, entry_point_id)
);

ALTER TABLE sessions ADD COLUMN chat_account_id UUID;         -- L1 account (NULL for anon)
ALTER TABLE sessions ADD COLUMN entry_point_id  VARCHAR(64);

-- The resume lookup: "latest session for this account at this entry point."
CREATE INDEX sessions_account_resume_ix
    ON sessions (chat_account_id, entry_point_id, created_at DESC);
