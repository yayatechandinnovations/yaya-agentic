-- Policies / ABAC (milestone I3, design §5.7, §7, §14). Conditional rules layered
-- on RBAC and evaluated by the PDP. A DENY policy carries TWO distinct reasons
-- (non-negotiable #3): user_reason (may be shown) and audit_reason (internal).

create table policies (
    id             varchar(64)  primary key,
    org_id         varchar(64)  not null,
    name           varchar(128) not null,
    effect         varchar(16)  not null,          -- PERMIT | DENY
    target_json    jsonb        not null,          -- { "actions": [...], "resourceTypes": [...] }
    condition_json jsonb,                            -- JSON-logic over subject/resource/env; null = unconditional
    priority       int          not null default 0,
    user_reason    text,                            -- DENY only: user-safe reason (#3)
    audit_reason   text,                            -- DENY only: internal reason (#3, must differ)
    active         boolean      not null default true,
    created_at     timestamptz  not null default now(),
    constraint policies_effect_ck check (effect in ('PERMIT', 'DENY')),
    constraint policies_org_fk    foreign key (org_id) references organizations (id)
);
create index policies_org_active_idx on policies (org_id, active);
