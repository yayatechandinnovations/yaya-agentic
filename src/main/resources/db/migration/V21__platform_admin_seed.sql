-- M5a — synthetic platform-admin user + OWNER memberships for every
-- existing tenant. See docs/specs/user-accounts/Backend-Data.md §4.2.
--
-- The synthetic user (UUID 0...01) is the audit identity for bootstrap-
-- source operator sessions and the "owner" of every pre-M5a tenant until a
-- real user takes over. password_hash = '' is constant-time false against
-- argon2.matches; the only way to authenticate as this user is through the
-- bootstrap operator path (which derives its hash from env, not this row).

INSERT INTO users (
    id,
    email,
    display_name,
    password_hash,
    status,
    is_platform_admin,
    email_verified_at
) VALUES (
    '00000000-0000-0000-0000-000000000001',
    'platform-admin@local',
    'Platform Admin (synthetic)',
    '',
    'ACTIVE',
    true,
    now()
);

INSERT INTO user_workspace_memberships (
    user_id,
    workspace_id,
    role,
    added_by,
    added_at
)
SELECT
    '00000000-0000-0000-0000-000000000001',
    t.id,
    'OWNER',
    '00000000-0000-0000-0000-000000000001',
    now()
FROM tenants t;
