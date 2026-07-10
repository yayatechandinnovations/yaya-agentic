-- Phone-verified self-registration (design §5.2 addendum). A signup is a PENDING
-- user (INVITED status, password already set) plus the org it will own once the
-- phone OTP is confirmed. The OTP itself lives in credentials (PHONE_OTP) or at
-- the external verifier (Twilio Verify) — never here.

create table pending_signups (
    id          varchar(64)  primary key,
    user_id     varchar(64)  not null,
    org_name    varchar(200) not null,
    phone       varchar(32)  not null,
    status      varchar(16)  not null default 'PENDING',
    otp_sent_at timestamptz,
    created_at  timestamptz  not null default now(),
    expires_at  timestamptz  not null,
    constraint pending_signups_status_ck check (status in ('PENDING', 'VERIFIED', 'EXPIRED')),
    constraint pending_signups_user_fk   foreign key (user_id) references users (id)
);
create index pending_signups_user_idx  on pending_signups (user_id);
create index pending_signups_phone_idx on pending_signups (phone);
