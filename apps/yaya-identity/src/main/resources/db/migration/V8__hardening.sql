-- Hardening (milestone I5, design §11). PII deletion (AC-44), per-org MFA policy.

-- DELETED status + a tombstone timestamp; PII columns are nulled on delete but
-- the row (and its id) survives so audit references stay intact.
alter table users drop constraint users_status_ck;
alter table users add constraint users_status_ck
    check (status in ('INVITED', 'ACTIVE', 'LOCKED', 'DISABLED', 'DELETED'));
alter table users add column deleted_at timestamptz;

-- MFA required for privileged roles in this org (owner/admin).
alter table organizations add column mfa_required boolean not null default false;
