-- RBAC (milestone I2, design §5.4–5.5, §14). Permissions catalog + app manifest,
-- roles, groups, and SCOPED role assignments — the "manager of store A but not B"
-- primitive. All intra-schema; no cross-schema references.

-- The universe of permission keys, "<app>.<resource>:<action>".
create table permissions (
    key varchar(128) primary key
);

-- Which app declared which permission (the manifest). An app owns its namespace.
create table app_permissions (
    app            varchar(64)  not null,
    permission_key varchar(128) not null,
    primary key (app, permission_key),
    constraint app_permissions_key_fk foreign key (permission_key) references permissions (key)
);

-- Roles: system roles have org_id null and are available to every org.
create table roles (
    id         varchar(64)  primary key,
    org_id     varchar(64),
    name       varchar(128) not null,
    system     boolean      not null default false,
    created_at timestamptz  not null default now(),
    constraint roles_org_fk foreign key (org_id) references organizations (id)
);
-- A role name is unique within its org; system roles (null org) are unique globally.
create unique index roles_org_name_uk on roles (coalesce(org_id, '~system'), name);

create table role_permissions (
    role_id        varchar(64)  not null,
    permission_key varchar(128) not null,
    primary key (role_id, permission_key),
    constraint role_permissions_role_fk foreign key (role_id) references roles (id),
    constraint role_permissions_key_fk  foreign key (permission_key) references permissions (key)
);

create table groups (
    id         varchar(64)  primary key,
    org_id     varchar(64)  not null,
    name       varchar(128) not null,
    created_at timestamptz  not null default now(),
    constraint groups_org_fk foreign key (org_id) references organizations (id),
    constraint groups_org_name_uk unique (org_id, name)
);

create table group_members (
    group_id varchar(64) not null,
    user_id  varchar(64) not null,
    primary key (group_id, user_id),
    constraint group_members_group_fk foreign key (group_id) references groups (id),
    constraint group_members_user_fk  foreign key (user_id)  references users (id)
);

-- Scoped role assignments. A subject (USER or GROUP) holds a role within a scope:
-- ORG (all resources in org_id) or RESOURCE (one resource, e.g. store:st_123).
-- org_id is always the owning org, so effective-permission resolution is org-bounded.
create table role_assignments (
    id           varchar(64) primary key,
    org_id       varchar(64) not null,
    subject_kind varchar(16) not null,     -- USER | GROUP
    subject_id   varchar(64) not null,
    role_id      varchar(64) not null,
    scope_kind   varchar(16) not null,     -- ORG | RESOURCE
    scope_type   varchar(64),              -- resource type when RESOURCE (e.g. 'store')
    scope_id     varchar(64),              -- resource id when RESOURCE; org id when ORG
    granted_at   timestamptz not null default now(),
    constraint role_assignments_subject_ck check (subject_kind in ('USER', 'GROUP')),
    constraint role_assignments_scope_ck   check (scope_kind in ('ORG', 'RESOURCE')),
    constraint role_assignments_role_fk    foreign key (role_id) references roles (id),
    constraint role_assignments_org_fk     foreign key (org_id)  references organizations (id)
);
create index role_assignments_lookup_idx on role_assignments (org_id, subject_kind, subject_id);
create unique index role_assignments_uk
    on role_assignments (org_id, subject_kind, subject_id, role_id, scope_kind,
                         coalesce(scope_type, ''), coalesce(scope_id, ''));
