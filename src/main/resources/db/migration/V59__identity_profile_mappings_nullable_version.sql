-- Latest-active profile resolution (design §5.2).
--
-- identity_profile_mappings.profile_version becomes NULLABLE with no default:
--   NULL  => unpinned. The IdentityProfileResolver emits an unpinned ProfileId
--            and the engine resolves it to the LATEST published version once,
--            at session start (then pins the session there for its lifetime).
--   value => pinned. That exact version is served, unchanged.
--
-- Existing rows keep their concrete profile_version (they were written under
-- the old NOT NULL DEFAULT 1), so their behavior does not change — they stay
-- pinned. Only mappings created/updated without a version from now on store
-- NULL and follow "latest".
ALTER TABLE identity_profile_mappings ALTER COLUMN profile_version DROP DEFAULT;
ALTER TABLE identity_profile_mappings ALTER COLUMN profile_version DROP NOT NULL;
