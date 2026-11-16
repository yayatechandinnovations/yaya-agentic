# WhatsApp shared templates — one approval per business account, shared across workspaces

> Companion to `whatsapp-channel-design.md` §7 (Twilio account), §11.1 (templates registry),
> `docs/guides/whatsapp-sender-registration.md` §0 (the two registration paths) and, on the
> Smart Lottery side, `smart-lottery-v1/docs/whatsapp-ops-integration-design.md` §6.3, §14.5–14.6.

| | |
|---|---|
| **Status** | 🟢 Implemented 2026-11-16 (stages 1–4), tests green. Not committed, not deployed |
| **Owner** | Yenfri Herrera |
| **Last updated** | 2026-11-16 |
| **Scope** | Store a WhatsApp template once per WhatsApp Business Account and let several workspaces use it. Platform admins decide which workspace gets which template. Both sender registration paths stay. |
| **Out of scope** | Sharing an approval between two different WhatsApp Business Accounts (Meta does not allow it). Changing how senders are registered. Other providers than Twilio. |

---

## 1. The problem

Meta approves a template for one **WhatsApp Business Account**. Every number in that account can
send it. A Twilio account holds exactly one such account, so in platform terms the unit of
approval is the Twilio account a sender speaks through:

| Registration path | Twilio account | Who shares approvals |
|---|---|---|
| «Start sender registration» | The platform's account | Every number on this path, across all workspaces |
| «Connect WhatsApp» (Embedded Signup) | A dedicated subaccount per customer | The numbers of that one customer |

The platform does not model this. `whatsapp_templates` is keyed by sender
(`UNIQUE (sender_id, name, locale)`, V29), and every template route sits under
`/v1/admin/tenants/{t}/whatsapp-senders/{s}/templates`. Each sender carries its own copy of the
content, the Twilio content sid and the approval status.

State on production, read 2026-11-16:

- Two real senders, `fastcash` (+18455336210) and `test` (+15024874573). Both are on the platform
  account (`messaging_account_id` is null).
- `whatsapp_messaging_accounts` is empty. No customer has connected their own account.
- `whatsapp_templates` is empty. None of the 19 Smart Lottery catalogue templates is registered.

Most customers are expected to stay on the platform account. With the current model, 19
templates and N customers mean 19 × N template records and 19 × N approval requests for what
Meta treats as 19 templates.

### 1.1 What goes wrong today, beyond the repetition

1. **Second submission of the same template.** `submit` finds the existing Twilio content by its
   friendly name (name + locale + content hash) and then calls
   `POST /v1/Content/{sid}/ApprovalRequests/whatsapp` again for that same content. What Twilio
   answers to a repeated request is **not verified**. If it is an error, the second workspace's
   row ends as `submit_failed` although the template is already approved.
2. **Same name, different wording.** Meta allows one content per name and language in an account.
   Two workspaces on the platform account that declare the same name with different bodies get
   two platform rows, but only one can ever be approved. Nothing in the platform reports the
   clash.
3. **No account-wide view.** A platform admin cannot see which templates exist on the platform
   account or which workspaces use them.

---

## 2. Goals

1. One template record, one Twilio content and one approval request per (account, name, locale).
2. A workspace can send a template only when it holds it. Platform admins decide who holds what.
3. Smart Lottery's one-click S8 keeps working without changes and stops waiting for Meta from the
   second customer on.
4. Customers with their own account keep working; their two or more numbers also share.
5. No change to how a message is sent, other than the lookup.

---

## 3. Model

Two terms used below:

- **Account**: where a sender's templates live. `platform` for senders without a messaging
  account, otherwise the sender's `messaging_account_id` (`wma_…`).
- **Holder**: a workspace allowed to use a template.

### 3.1 Tables (migration V94)

`whatsapp_templates` is reshaped in place, so template ids survive:

```
whatsapp_templates(
    id, name, locale, category, body, placeholder_count,
    header_kind, buttons_json, samples_json, content_hash,
    bsp_template_id, bsp_content_sid,
    status, rejection_reason, submitted_at, approved_at, last_sync_at,
    account_key        VARCHAR(64) NOT NULL,   -- 'platform' | wma_…        (new)
    created_by_tenant  VARCHAR(64),            -- audit only, null = console (new)
    UNIQUE (account_key, name, locale)         -- replaces (sender_id, name, locale)
)                                              -- tenant_id, sender_id dropped

whatsapp_template_holders(                     -- new
    template_id  BIGINT      NOT NULL REFERENCES whatsapp_templates(id) ON DELETE CASCADE,
    tenant_id    VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    granted_by   VARCHAR(128) NOT NULL,        -- operator id or api-key prefix
    granted_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (template_id, tenant_id)
)

whatsapp_template_holder_events(               -- new: who shared what with whom
    id, template_id, account_key, template_name, locale, tenant_id,
    action       VARCHAR(16)  NOT NULL,        -- SHARED | UNSHARED
    actor        VARCHAR(128) NOT NULL,
    occurred_at  TIMESTAMPTZ  NOT NULL
)                                              -- no foreign keys: it outlives the holder and the template
```

There is no owner column. What a caller may do depends only on whether its workspace is a
holder and whether the caller is a platform admin (§4).

The platform had no audit store for channel operations, so sharing has its own events table:
one row each time a workspace starts or stops holding a template, with the operator or API key
that did it (`source:subject`).

### 3.2 Migration of existing rows

Production has no rows. Other databases may, so V94 handles them:

1. Set `account_key` from the row's sender (`messaging_account_id`, or `platform`).
2. Within each (account, name, locale) group keep one row: the most advanced status
   (APPROVED, SUBMITTED, PAUSED, REJECTED, DRAFT), then the lowest id.
3. Every tenant whose row in the group has the same `content_hash` as the kept row becomes a
   holder, with `granted_by = 'migration-v94'`.
4. Rows with a different hash are deleted. They could not have been approved under that name in
   the same account.

### 3.3 Where the account comes from at runtime

`WhatsAppSender` already carries `messagingAccountId`. One helper,
`TemplateAccounts.keyOf(sender)`, returns `platform` or the id. `ProviderCredsResolver` gains
`resolveForAccount(accountKey)`, so the approval sweep no longer needs a sender to obtain
credentials.

---

## 4. Rules

### 4.1 Who may hold a template

| Account | Possible holders |
|---|---|
| `platform` | Any workspace |
| `wma_…` (customer's own) | Only the workspace that owns that account |

### 4.2 Declare — `PUT …/whatsapp-senders/{s}/templates/by-name/{name}` (path unchanged)

The account is the sender's account. The caller is a workspace admin, or a platform admin or
platform-scope API key acting on that workspace.

| Existing template in the account | Caller's workspace is a holder | Caller is a platform admin | Result |
|---|---|---|---|
| None | n/a | either | Created as DRAFT; the workspace becomes a holder |
| Same content | yes | either | No-op, returns the current status |
| Same content | no | yes | The workspace becomes a holder; returns the current status. No new submission |
| Same content | no | no | `409 template_name_taken` |
| Different content, DRAFT or REJECTED | sole holder | either | Edited in place, back to DRAFT |
| Different content, DRAFT or REJECTED | one of several | yes | Edited in place for all holders, back to DRAFT |
| Different content, DRAFT or REJECTED | no | yes | Edited in place for all holders, back to DRAFT; the workspace becomes a holder |
| Different content, DRAFT or REJECTED | one of several, or no | no | `409 not_editable` / `409 template_name_taken` |
| Different content, SUBMITTED, APPROVED or PAUSED | any | either | `409 not_editable` (bump the name, as today) |

`template_name_taken` returns no content and no status of the existing template.

The table is for the platform account. On a workspace's own account the workspace is the only
possible holder, so a template it does not hold (it stopped using it earlier) is taken back by
its own declaration, without a platform admin.

Two workspaces declaring the same new name at the same moment: the unique key lets one insert
win, and the controller repeats the other's declare once, which then finds the row.

The console's own create and edit calls (`POST …/templates`, `PUT …/templates/{id}`) follow the
same rules: a name another workspace holds is `template_name_taken`, and a workspace admin
cannot edit a template that other workspaces hold. Both now store a content hash, and an edit
clears the stored content sid, so a reworded template is never resubmitted with its old content.

### 4.3 Submit, sync, list, delete (paths unchanged)

- **Submit**: allowed to a holder, or a platform admin, when the status is DRAFT or REJECTED.
  It runs once per template: the row is locked for the submission, and a template that is
  already SUBMITTED or APPROVED is returned as it is, so a repeated or concurrent call never
  reaches Twilio. A PAUSED template is `409 not_submittable`.
- **Sync** and the scheduled sweep run per template, with the account's credentials.
- **List** (`GET …/whatsapp-senders/{s}/templates`): the templates of the sender's account that
  the workspace holds. The response keeps its shape and adds `shared: true` when the template
  has other holders. Other holders are never named to a workspace.
- **Delete** by a workspace removes that workspace as a holder. The template row is deleted only
  when no holder remains and the status is DRAFT or REJECTED. An approved template without
  holders stays in the account for a platform admin to share again.

Behaviour change: today, deleting an APPROVED template sets it to PAUSED on the platform only.
After this change PAUSED always means Meta paused it.

### 4.4 Send

`WhatsAppOutreachLeg.sendTemplate` calls `findApproved(tenant, sender, name, locale)`. The lookup
becomes: template by (account of the sender, name, locale), status APPROVED, and the tenant is
a holder. Anything else falls back as `template_not_approved`, exactly as today.

---

## 5. Platform-admin surface

Platform admins and platform-scope keys only.

| Call | Purpose |
|---|---|
| `GET /v1/admin/whatsapp-accounts` | The platform account and each connected account, with template counts |
| `GET /v1/admin/whatsapp-accounts/{accountKey}/templates` | Every template of the account with status and holders |
| `PUT /v1/admin/whatsapp-accounts/{accountKey}/templates/by-name/{name}` | Declare a template with no workspace (body = the §14.5 declaration) |
| `POST /v1/admin/whatsapp-accounts/{accountKey}/templates/{id}/submit` | Submit it |
| `POST /v1/admin/whatsapp-accounts/{accountKey}/templates/sync` | Refresh the approval states of the account from the provider |
| `DELETE /v1/admin/whatsapp-accounts/{accountKey}/templates/{id}` | Remove a template nobody holds and WhatsApp never approved (`409 template_in_use`, `409 not_deletable`) |
| `PUT /v1/admin/whatsapp-accounts/{accountKey}/templates/{id}/holders/{tenantId}` | Share with a workspace (`409 holder_not_allowed` per §4.1) |
| `DELETE /v1/admin/whatsapp-accounts/{accountKey}/templates/{id}/holders/{tenantId}` | Stop sharing |

The route prefix is new. Anyone else gets `403 platform_admin_required`
(`WorkspaceAccessHelper.requirePlatformAdmin`). Every share and unshare, from these routes or
from a workspace route, writes a row in `whatsapp_template_holder_events`.

### 5.1 Console (`yayaagenticweb`)

- **New page «Shared WhatsApp templates»** (`/admin/whatsapp-shared-templates`, Platform group,
  platform admins only). Pick an account, see each template once: name, language, status,
  rejection reason, and a chip per workspace that uses it. Actions: share with a workspace, stop
  sharing, submit, refresh status, remove, and add a text-only template (name, language,
  category, body, one example value per placeholder). Templates with a document header or
  buttons come from the integration that declares them.
- **Existing page `/admin/whatsapp-templates`** keeps the sender picker. A shared template shows
  a «Shared» tag and can be edited only by a platform admin. «Remove» reads «Stop using» when the
  template stays on the account (other workspaces use it, or WhatsApp already has it).

---

## 6. Smart Lottery hub

One-click S8 (`YayaOpsWhatsAppProvisioner`) needs no code change:

- It declares each catalogue template for the customer's sender. With the hub's platform-scope
  key (`SL_YAYA_AGENTIC_PLATFORM_KEY`), the first customer creates and submits the template; each
  later customer on the platform account becomes a holder and gets the current status back.
- It submits only when the returned status is DRAFT or REJECTED, so later customers skip the
  submission.
- Its status block reads the per-sender list, which keeps its shape.

A hub configured with per-workspace keys instead of the platform-scope key gets
`template_name_taken` for the second customer. The hub shows that template as «Nombre en uso en
el número compartido» with the fix: configure the platform key, or share the template from the
platform console.

Added in the hub: **«Registrar plantillas en todos los negocios»**
(`POST /api/customer-admin/yayaops/whatsapp-templates/register-all`, button in the WhatsApp block
of the one-click card):

- It covers every business that holds an ops catalog (`owner-ops` or `messenger-ops`), that is,
  every business that already ran the one-click, and only those the caller's partner scope
  reaches. A business that never ran the one-click gets nothing.
- For each business it declares the catalog's templates on the active number and submits the
  ones WhatsApp has not seen. It does not touch the sender links or the install.
- It does not ask the provider for approval states; it reports the platform's current ones,
  which the platform's own sweep refreshes every ten minutes. A run is a list of short calls.
- One business never stops the others. The result lists each business as registered, without a
  WhatsApp number, or failed, and names the templates that are not approved or in review.

---

## 7. Risks

| Risk | Handling |
|---|---|
| A workspace misuses a shared template and Meta pauses it for every holder | Sharing is a platform-admin decision; unshare removes the workspace at once. The account's quality rating is already shared by every number on it |
| A workspace on the platform account takes a name first | Names are per account at Meta, so this exists today. The clash now returns `template_name_taken`, and a platform admin sees the template and its holders |
| The platform's Twilio account is replaced | The `platform` templates keep content sids of the old account. Reset them to DRAFT and submit again |
| Repository rules on workspace scoping | Lookups by `account_key` are marked `@WorkspaceUnscoped`; every tenant-facing read goes through the holders table |

---

## 8. Stages

| Stage | Content | Repo |
|---|---|---|
| 1 | V94, entity and repository reshape, `TemplateAccounts`, `resolveForAccount`, declare / submit / sync / list / delete rules, send lookup, sweep per template | yaya-agentic backend |
| 2 | Platform-admin API (§5), audit entries | yaya-agentic backend |
| 3 | «Shared templates» page, «Shared» tag and «Stop using» on the existing page | yayaagenticweb |
| 4 | «Registrar plantillas en todos los negocios» | smart-lottery-customer-admin |

Tests (all green, 2026-11-16):

| Test | Covers |
|---|---|
| `SharedTemplatesMigrationTest` | V94 on a V93 database: two workspaces on the platform account with the same template, the same name with different content, a workspace on its own account; the new unique key; the tenant cascade |
| `SharedTemplatesTest` | §4.2 rules against a real database with a mock provider: sharing by a platform declaration, `template_name_taken`, edits, one submission per template, send lookup for holders only, stop using, the events trail, own accounts, the platform-admin routes |
| `PlatformAdminGateTest` | Platform admins and platform-scope keys pass; workspace members and pinned keys get 403 |
| `WhatsAppTemplateControllerTest`, `WhatsAppOutreachLegTest`, `TemplateContentDefinitionTest`, `RepositoryScanTest` | Existing behaviour and the workspace-scoping rule still hold |
| `whatsapp_shared_templates_screen_test.dart` (console, `--platform chrome`) | The page lists each template once with its workspaces; share, stop sharing, add |
| `YayaOpsWhatsAppProvisionerTest`, `YayaOpsTemplateRolloutServiceTest` (hub) | Templates-only registration, the name-taken message, the rollout over businesses |
| `yayaops_templates_everywhere_test.dart` (hub UI) | Confirmation, the call, the result per business |

Rollout: back up `whatsapp_templates` before V94, deploy the platform, then run S8 for
`fastcash` and for `test` and confirm that `test` shows the same statuses without a second
submission. Each deploy needs the owner's go-ahead.

---

## 9. Decisions

Implemented as recommended (owner, 2026-11-16: «have this fully implemented»).

| # | Decision | Implemented | Alternative not taken |
|---|---|---|---|
| D1 | A declare made with a platform-scope key shares the template with that workspace | Yes. The hub's one-click needs no change and no manual step per customer | Sharing only from the console; the one-click fails for the second customer until someone shares |
| D2 | A workspace admin declares a name that exists with identical content | Refused (`template_name_taken`); a platform admin shares it | Accept it automatically. Less work, but any workspace that knows the wording can attach itself to a template whose pause would hit the others |
| D3 | Deleting an approved template | Removes the workspace as holder; PAUSED is Meta's state only | Keep today's platform-only PAUSED |
