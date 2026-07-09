-- Credentials (milestone I1c, design §5.2 / §6.2, module 3). A user may hold
-- several credentials of different kinds. secret_ref never stores a raw secret:
-- PASSWORD and PHONE_OTP are Argon2id-hashed; TOTP seeds (I5) are AES-256-GCM
-- encrypted. Within the yaya_identity schema (intra-schema FK to users only).

create table credentials (
    id          varchar(64)  primary key,
    user_id     varchar(64)  not null,
    kind        varchar(16)  not null,        -- PASSWORD | PHONE_OTP | TOTP
    secret_ref  text         not null,        -- Argon2id hash (password/OTP) or AES-GCM seed (TOTP)
    status      varchar(16)  not null default 'ACTIVE',
    created_at  timestamptz  not null default now(),
    expires_at  timestamptz,                  -- OTP validity window
    consumed_at timestamptz,                  -- single-use OTP consumption
    attempts    int          not null default 0,
    constraint credentials_kind_ck   check (kind in ('PASSWORD', 'PHONE_OTP', 'TOTP')),
    constraint credentials_status_ck check (status in ('ACTIVE', 'EXPIRED', 'CONSUMED', 'REVOKED')),
    constraint credentials_user_fk   foreign key (user_id) references users (id)
);
create index credentials_user_kind_idx on credentials (user_id, kind, status);

-- At most one ACTIVE password per user (a reset revokes the previous one).
create unique index credentials_one_active_password
    on credentials (user_id) where kind = 'PASSWORD' and status = 'ACTIVE';
