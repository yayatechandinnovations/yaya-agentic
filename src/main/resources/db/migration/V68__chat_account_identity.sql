-- Chat account self-service (chat-account-self-service-design.md §5, §9.1).
--
-- Until now the chat account WAS its phone: `phone_e164` was the primary key and
-- the principal subject is `chat:+E164`. That makes a phone change an identity
-- change — it would orphan every session recorded under the old subject. This
-- migration makes a stable UUID the identity and demotes the phone to a mutable,
-- unique attribute, so a later phone change touches exactly one row and history
-- (joined by account id, not phone string) survives.
--
-- It also adds the revocation primitive: `pass_gen`. A device pass is valid only
-- if its `gen` claim matches the account's current `pass_gen`; bumping it (on
-- delete, phone change, or sign-out-everywhere) instantly revokes every
-- outstanding pass with no per-token deny-list.

ALTER TABLE chat_accounts ADD COLUMN id UUID;
UPDATE chat_accounts SET id = gen_random_uuid() WHERE id IS NULL;
ALTER TABLE chat_accounts ALTER COLUMN id SET NOT NULL;

ALTER TABLE chat_accounts ADD COLUMN status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE chat_accounts ADD COLUMN pass_gen   INT         NOT NULL DEFAULT 1;
ALTER TABLE chat_accounts ADD COLUMN deleted_at TIMESTAMPTZ;

-- Swap the primary key: the UUID is now the identity; the phone becomes a plain
-- (uniquely-indexed) attribute.
ALTER TABLE chat_accounts DROP CONSTRAINT chat_accounts_pkey;
ALTER TABLE chat_accounts ADD  CONSTRAINT chat_accounts_pkey PRIMARY KEY (id);

-- Uniqueness is enforced only among ACTIVE rows, so a soft-deleted (DELETED) row
-- during its GDPR grace window does NOT block the same phone re-registering.
CREATE UNIQUE INDEX chat_accounts_active_phone_uq
    ON chat_accounts (phone_e164) WHERE status = 'ACTIVE';
