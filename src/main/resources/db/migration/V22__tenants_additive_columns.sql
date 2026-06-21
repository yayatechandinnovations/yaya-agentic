-- M5a — additive columns on tenants. See
-- docs/specs/user-accounts/Backend-Data.md §3 and §4.3.
--
-- This is the FIRST half of the UUID cutover. We add the new columns and
-- backfill them; child-table FKs stay on tenants.id (the slug) for now.
-- The breaking half (V23 child FK rewrite + V24 PK swap) lives in a
-- follow-up release once we've soaked the additive schema in production.
--
-- After this migration:
--   * tenants.code         = the human-readable slug (today == tenants.id)
--   * tenants.id_uuid      = stable machine identifier; FKs migrate to it in V23
--   * tenants.owner_user_id = user who owns the workspace; service-side maintained
--
-- Columns that already exist from V9 are NOT re-added:
--   * created_by  (VARCHAR(255); already populated with UUID strings by M5a code)
--   * archived_at (TIMESTAMPTZ; WorkspaceService.archive() already writes it)

ALTER TABLE tenants
    ADD COLUMN code           VARCHAR(64),
    ADD COLUMN id_uuid        UUID,
    ADD COLUMN owner_user_id  UUID;

-- Backfill. tenants.id is the slug today; mirror it into code.
UPDATE tenants SET code = id WHERE code IS NULL;
UPDATE tenants SET id_uuid = gen_random_uuid() WHERE id_uuid IS NULL;

-- owner_user_id: parse the existing created_by (if it's a UUID string from
-- M5a code) — otherwise fall back to the synthetic platform admin from V21.
UPDATE tenants
   SET owner_user_id = CAST(created_by AS UUID)
 WHERE owner_user_id IS NULL
   AND created_by IS NOT NULL
   AND created_by ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$';

UPDATE tenants
   SET owner_user_id = '00000000-0000-0000-0000-000000000001'
 WHERE owner_user_id IS NULL;

-- Lock the new columns NOT NULL now that backfill completed.
-- id_uuid gets a DEFAULT so new inserts can stay unaware of the column
-- (JPA marks it insertable=false until V24 swaps the PK).
ALTER TABLE tenants
    ALTER COLUMN code          SET NOT NULL,
    ALTER COLUMN id_uuid       SET NOT NULL,
    ALTER COLUMN id_uuid       SET DEFAULT gen_random_uuid(),
    ALTER COLUMN owner_user_id SET NOT NULL;

-- code is the slug used in URLs — must be globally unique.
ALTER TABLE tenants ADD CONSTRAINT tenants_code_uniq UNIQUE (code);

-- id_uuid will become the PK in V24; uniqueness here lets V23 add FKs
-- pointing at it without re-validating row by row.
ALTER TABLE tenants ADD CONSTRAINT tenants_id_uuid_uniq UNIQUE (id_uuid);

ALTER TABLE tenants
    ADD CONSTRAINT tenants_owner_user_id_fk
        FOREIGN KEY (owner_user_id) REFERENCES users(id);

CREATE INDEX tenants_owner_user_id_idx ON tenants (owner_user_id);
