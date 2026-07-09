-- Clients / machine identity (milestone I4, design §5.6, §14). Relying-party apps
-- and service accounts. Replaces the in-memory client seed. Secrets are stored
-- hashed (delegating {argon2}/{bcrypt}); PUBLIC clients have none.

create table clients (
    id                 varchar(64)  primary key,
    client_id          varchar(128) not null unique,
    org_id             varchar(64),                    -- null = system/platform client
    name               varchar(255),
    type               varchar(16)  not null,          -- PUBLIC | CONFIDENTIAL | SERVICE
    secret_hash        text,                            -- null for PUBLIC
    grants_json        jsonb        not null,
    redirect_uris_json jsonb,
    scopes_json        jsonb,
    audiences_json     jsonb,                           -- token aud binding (§5.6)
    require_pkce       boolean      not null default false,
    created_at         timestamptz  not null default now(),
    constraint clients_type_ck check (type in ('PUBLIC', 'CONFIDENTIAL', 'SERVICE')),
    constraint clients_org_fk  foreign key (org_id) references organizations (id)
);
