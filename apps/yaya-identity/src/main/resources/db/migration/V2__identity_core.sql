-- Identity core (milestone I1): organizations, users, memberships (design §5.1–5.2,
-- §14). Still fully within the yaya_identity schema — intra-schema FKs only, no
-- cross-schema references (SchemaIsolationLintTest enforces it).

create table organizations (
    id            varchar(64)  primary key,
    name          varchar(255) not null,
    slug          varchar(255) not null unique,
    status        varchar(16)  not null default 'ACTIVE',
    parent_id     varchar(64),                    -- reserved: flat now, nestable later (design §16 Q6)
    metadata_json jsonb,
    created_at    timestamptz  not null default now(),
    constraint organizations_status_ck check (status in ('ACTIVE', 'SUSPENDED')),
    constraint organizations_parent_fk foreign key (parent_id) references organizations (id)
);

-- A user is a global identity (PERSON or SERVICE); membership ties it to an org.
create table users (
    id            varchar(64)  primary key,
    primary_email varchar(320),
    phone         varchar(32),
    display_name  varchar(255),
    kind          varchar(16)  not null,
    status        varchar(16)  not null,
    created_at    timestamptz  not null default now(),
    constraint users_kind_ck   check (kind in ('PERSON', 'SERVICE')),
    constraint users_status_ck check (status in ('INVITED', 'ACTIVE', 'LOCKED', 'DISABLED'))
);
-- Case-insensitive email uniqueness (partial: SERVICE users may have no email).
create unique index users_email_lower_uk on users (lower(primary_email)) where primary_email is not null;

create table memberships (
    user_id   varchar(64) not null,
    org_id    varchar(64) not null,
    status    varchar(16) not null default 'ACTIVE',
    joined_at timestamptz not null default now(),
    primary key (user_id, org_id),
    constraint memberships_status_ck check (status in ('ACTIVE', 'SUSPENDED')),
    constraint memberships_user_fk foreign key (user_id) references users (id),
    constraint memberships_org_fk  foreign key (org_id)  references organizations (id)
);
create index memberships_org_idx on memberships (org_id);
