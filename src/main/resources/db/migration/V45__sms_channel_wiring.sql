-- S3 — wire SMS into the shared channel infrastructure (bindings, sessions,
-- inbound/outbound messages). All changes are additive / relaxing so the
-- shipped WhatsApp path is unaffected. See `sms-channel-design.md` §5.
--
-- 1. Let channel_bindings route to an SMS sender endpoint kind.
ALTER TABLE channel_bindings DROP CONSTRAINT channel_bindings_endpoint_kind_chk;
ALTER TABLE channel_bindings ADD CONSTRAINT channel_bindings_endpoint_kind_chk
    CHECK (endpoint_kind IN ('WHATSAPP_SENDER','SMS_SENDER'));

-- 2. Make channel_sessions channel-agnostic. It was FK-bound to
--    whatsapp_senders; SMS sender ids ('sms_...') can't satisfy that. Drop the
--    FK so sender_id is a polymorphic reference (it already behaves like one in
--    channel_bindings). Sender ids are globally unique across both sender
--    tables (distinct prefixes), so the OPEN-session unique index still holds.
ALTER TABLE channel_sessions DROP CONSTRAINT IF EXISTS channel_sessions_sender_id_fkey;

-- 3. Record billed SMS segments per outbound message (design §5.1). Nullable +
--    additive; the WhatsApp dispatcher simply leaves it null.
ALTER TABLE outbound_messages ADD COLUMN segment_count INT;
