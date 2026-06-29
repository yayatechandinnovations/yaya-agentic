-- Per-resolver JOSE 'kid' for the outbound SIGNED_JWT header, plus an
-- optional audience override. Both nullable + backward compatible: a row
-- with NULL kid mints exactly today's header (no keyID), and a row with
-- NULL audience keeps aud = tenant_id. See
-- docs/design/channel-auth-resolver-signing-kid.md.
--
-- service_jwt_kid is NOT a secret — it is a public key identifier carried
-- in the JWT header in cleartext, so unlike service_secret_* it is stored
-- plain and is safe to return on the admin GET surface.
--
-- kid/audience are only meaningful for HTTP + SIGNED_JWT. We do NOT add a
-- CHECK forcing that shape: a stale value on a row later switched to
-- NONE/BEARER is harmless — the mint path is the only reader and it only
-- runs for SIGNED_JWT. This matches the existing table, which also lets
-- service_auth_kind / service_secret coexist loosely.

ALTER TABLE channel_auth_resolvers
    ADD COLUMN service_jwt_kid      VARCHAR(128),
    ADD COLUMN service_jwt_audience VARCHAR(255);
