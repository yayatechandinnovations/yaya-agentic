-- Invitations (milestone I1c, design §5.2 / §10.2 — org onboarding is invite-gated,
-- US-20..US-22). Only the SHA-256 hash of the invite token is stored. Intra-schema
-- FK to organizations only.

create table invitations (
    id          varchar(64)  primary key,
    org_id      varchar(64)  not null,
    email       varchar(320),
    phone       varchar(32),
    role        varchar(128),                 -- role to grant on accept (RBAC at I2; string for now)
    token_hash  varchar(128) not null,
    invited_by  varchar(64),
    status      varchar(16)  not null default 'PENDING',
    created_at  timestamptz  not null default now(),
    expires_at  timestamptz  not null,
    accepted_at timestamptz,
    constraint invitations_status_ck check (status in ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    constraint invitations_org_fk    foreign key (org_id) references organizations (id)
);
create unique index invitations_token_uk on invitations (token_hash);
create index invitations_org_idx on invitations (org_id);
