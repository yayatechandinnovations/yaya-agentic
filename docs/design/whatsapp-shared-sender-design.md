# Platform WhatsApp senders — one number, many workspaces, staff-only conversations

> Companion to `whatsapp-channel-design.md` (§6 lifecycle, §8 bindings, §9 inbound, §10 outbound,
> §11.2 opt-out), `whatsapp-shared-templates-design.md` (templates per account + holders),
> `whatsapp-sender-transfer-design.md` (moving a number), `system-messaging-design.md` §20 (WhatsApp
> leg) and `channel-auth-resolver-design.md` (binding auth resolvers). Smart Lottery side:
> `smart-lottery-v1/docs/yayaops-one-click-provisioning-design.md` (S4/S6/S8),
> `smart-lottery-v1/docs/pos-ticket-whatsapp-share-design.md` (routes B/C, keyword actions).

| | |
|---|---|
| **Status** | 🟢 Platform side implemented 2026-11-24 (P1 + P2, V99; §18). Smart Lottery side (P3) implemented 2026-11-24. Not committed, not deployed. |
| **Owner** | Yenfri Herrera |
| **Scope** | **Platform senders**: WhatsApp numbers registered by platform admins that belong to no workspace. Platform admins **assign** workspaces to them. Every assigned workspace can **send** on the number (staff notifications, ticket copies, any outreach). Only a workspace's **registered staff** can **talk** to its assistant through it. Everyone else gets deterministic answers only: STOP/START, keyword actions and a fixed reply. |
| **Out of scope** | Customer (player) conversations on a platform sender. «Connect WhatsApp» numbers, which live in the customer's own WABA. SMS. Smart Lottery's install-wide Twilio `fromNumber` path (`WhatsAppProvider`, OTPs, kiosk recharge), which never goes through the platform. |

**Rev.2 (2026-11-23).** Owner: «register senders that are not tied to any workspace; we as admins
assign the workspaces». Rev.1's *host workspace* is gone. A sender now has an owner, either a
workspace (today's senders) or the platform. Platform senders are registered and managed only from
the platform-admin surface.

---

## 1. The problem

Meta caps the platform's WhatsApp Business Account at **2 numbers**. The business is already
verified, so verification won't raise that cap.

Today a number lives in exactly one workspace (`whatsapp_senders.tenant_id NOT NULL`,
`whatsapp_senders_live_phone_uniq`), and Smart Lottery maps **one SL org to one workspace**
(`tenantId = orgCode`, `YayaOpsOneClickService.java:126`). So at most two orgs can have WhatsApp at
all. Most orgs only need WhatsApp to:

- send staff notifications (undo requests, cash handoffs, routes, high prizes, reports, automations),
- send a ticket copy to the customer (route B), or let the customer scan for one (route C), and
- let staff (owner, supervisors, messengers) answer through the ops assistants
  (`owner_ops_v1`, `messenger_ops_v1`).

None of that requires each org to have its own number. What breaks with one number for many orgs is
**inbound**, because every inbound path takes the workspace from the sender:

| Where | Today |
|---|---|
| Webhook | `WhatsAppTwilioWebhookController.tenantOf(senderId)` → the sender's tenant |
| Worker | `InboundDispatchWorker` step 1: `senders.requireForTenant(row.tenantId, …)`; every later step uses `row.tenantId` |
| Session continuity | `channel_sessions_open_unique_idx` = `(sender_id, end_user_phone) WHERE OPEN`: one open session per phone per number, whatever the workspace |
| Keyword actions | `UNIQUE (sender_id, prefix)`: one forward URL per prefix per number |
| Outbound | `OutboundDispatcher` / `WhatsAppOutreachLeg`: `requireForTenant(tenant, sender)` |
| System-sender link | `SystemSenderService`: the linked number must be `findByTenantIdAndId(tenant, …)` |

A message arriving on a number that several workspaces use has to be assigned to one of them. That
is the **workspace router** ("org resolver") this design adds.

## 2. Goals

1. **Senders owned by the platform.** A platform admin registers, verifies, edits, suspends and
   removes them. No workspace owns one, and no workspace admin can change one.
2. **Assignments decided by admins.** A platform admin assigns workspaces to a platform sender and
   can pause or end each assignment. Assigned workspaces have no say over this.
3. **Keep one workspace per org.** Profiles, catalogs, resolvers, outbox, keyword forwarding, usage
   and audit stay per workspace. Nothing in Smart Lottery that keys by tenant has to learn about
   platform senders (§10.1).
4. **Outbound is unchanged for the workspace.** An assigned workspace links the platform sender to
   its system senders and sends exactly as it would on its own number.
5. **Only registered staff reach the engine, and only in a workspace that registered them.** Players
   never reach an assistant on a platform sender.
6. **Customers keep the deterministic paths they need to receive things.** That means STOP/START,
   route C («TICKET …»), «NO ES MÍO» and «NO MÁS TICKETS», plus a reply that tells them who wrote to
   them.
7. **Nothing crosses workspaces.** No workspace sees another's messages, contacts, members or
   outreach. Recipients always know which business a message is from.

## 3. Decisions

| Question | Decision | Why |
|---|---|---|
| Where does the number live? | **The sender is owned by the platform** (`owner_kind = PLATFORM`, `tenant_id NULL`) | Owner's call (rev.2). No customer workspace holds settings that affect other customers. Archiving a workspace can never take the number down. |
| How do orgs get it? | **Assignments** made by platform admins (`whatsapp_sender_assignments`) | Same pattern as template holders (`/v1/admin/whatsapp-accounts/{account}/templates/…/holders/{tenantId}`). |
| One workspace for all orgs, with an org field inside? | ❌ | `StaffTenantDirectoryImpl` uses `findFirstByTenantIdAndEnabledTrue` and would silently pick one org. `YayaOutreachConfigResolver.forTenant` refuses a tenant that several orgs share. Profiles, usage and transcripts would mix businesses. |
| Who decides the org of an inbound? | **A platform-side router over a member directory** that each workspace maintains (§7) | The alternatives fail. Fanning out to every workspace's auth resolver means N calls to different installations, a winner decided by latency, and no answer for staff of two orgs. An HTTP org resolver doesn't fit SL either: no single service knows staff phones across installations (base, basedl, …). The router SPI leaves room for an HTTP membership source later (§14). |
| Can customers talk on a platform sender? | **Never**, even with a single assignment | Predictable: assigning a second org later doesn't change what the first org's customers can do. A customer-facing number is a workspace sender. |

## 4. Model

### 4.1 Terms

- **Platform sender**: a `whatsapp_senders` row with `owner_kind = PLATFORM` and no tenant. It is
  always on the platform Twilio account.
- **Workspace sender**: every sender today (`owner_kind = WORKSPACE`). Unchanged.
- **Assignment**: `(platform sender, workspace)` plus a **label**, the business name shown to
  recipients. "Assigned workspace" below means a workspace with a live assignment.
- **Member**: a phone that an assigned workspace registered as staff on that sender.
- **Route**: the router's per-phone state on a platform sender: the active workspace, and any open
  «¿Con cuál negocio?» question.

### 4.2 Tables (migration V99)

```sql
-- Owner of a sender: a workspace (today) or the platform.
ALTER TABLE whatsapp_senders
    ADD COLUMN owner_kind VARCHAR(16) NOT NULL DEFAULT 'WORKSPACE',
    ADD COLUMN unrouted_reply TEXT,                         -- §8.3 generic reply; platform senders only
    ALTER COLUMN tenant_id DROP NOT NULL,
    ADD CONSTRAINT whatsapp_senders_owner_chk CHECK (
        (owner_kind = 'WORKSPACE' AND tenant_id IS NOT NULL) OR
        (owner_kind = 'PLATFORM'  AND tenant_id IS NULL AND messaging_account_id IS NULL));
-- whatsapp_senders_live_phone_uniq (V92) already spans every row, so a phone is live once,
-- whichever the owner.

CREATE TABLE whatsapp_sender_assignments (
    id                 VARCHAR(40)  PRIMARY KEY,              -- wsa_… (opaque; used in pick payloads)
    sender_id          VARCHAR(64)  NOT NULL REFERENCES whatsapp_senders(id),
    tenant_id          VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    label              VARCHAR(60)  NOT NULL,                 -- «Banca La Suerte»; platform admin only
    non_member_reply   TEXT,                                  -- §8.3; the workspace edits it
    daily_outbound_cap INT          NOT NULL DEFAULT 1000,    -- business-initiated sends per 24 h (§9.4)
    status             VARCHAR(16)  NOT NULL,                 -- ACTIVE | PAUSED | ENDED
    assigned_by        VARCHAR(128) NOT NULL,
    assigned_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ended_by           VARCHAR(128),
    ended_at           TIMESTAMPTZ,
    CHECK (status IN ('ACTIVE','PAUSED','ENDED'))
);
CREATE UNIQUE INDEX whatsapp_sender_assignments_live_uniq
    ON whatsapp_sender_assignments(sender_id, tenant_id) WHERE status <> 'ENDED';
CREATE UNIQUE INDEX whatsapp_sender_assignments_label_uniq
    ON whatsapp_sender_assignments(sender_id, lower(label)) WHERE status <> 'ENDED';

CREATE TABLE whatsapp_sender_assignment_events (  -- audit; no FKs, outlives the assignment
    id BIGSERIAL PRIMARY KEY, sender_id VARCHAR(64) NOT NULL, tenant_id VARCHAR(64) NOT NULL,
    action VARCHAR(24) NOT NULL,                  -- ASSIGNED | LABEL_CHANGED | CAP_CHANGED | PAUSED | RESUMED | ENDED
    detail_json JSONB, actor VARCHAR(128) NOT NULL, occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE whatsapp_sender_members (
    sender_id     VARCHAR(64)  NOT NULL REFERENCES whatsapp_senders(id) ON DELETE CASCADE,
    tenant_id     VARCHAR(64)  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    phone_e164    VARCHAR(20)  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (sender_id, tenant_id, phone_e164)
);
CREATE INDEX whatsapp_sender_members_phone_idx ON whatsapp_sender_members(sender_id, phone_e164);

CREATE TABLE whatsapp_shared_routes (
    sender_id          VARCHAR(64) NOT NULL REFERENCES whatsapp_senders(id) ON DELETE CASCADE,
    end_user_phone     VARCHAR(20) NOT NULL,
    active_tenant_id   VARCHAR(64),
    active_at          TIMESTAMPTZ,
    pending_choice     JSONB,           -- {assignmentIds:[…], heldMessageSid, askedAt}
    PRIMARY KEY (sender_id, end_user_phone)
);

-- One open session per phone per number PER WORKSPACE.
DROP INDEX channel_sessions_open_unique_idx;
CREATE UNIQUE INDEX channel_sessions_open_unique_idx
    ON channel_sessions(tenant_id, sender_id, end_user_phone) WHERE status = 'OPEN';

-- Platform-sender inbound has no workspace until the router picks one (§7.4);
-- platform-originated replies (STOP confirmation, pick question, generic reply) never get one.
ALTER TABLE inbound_messages  ALTER COLUMN tenant_id DROP NOT NULL, ADD COLUMN route_reason VARCHAR(32);
ALTER TABLE outbound_messages ALTER COLUMN tenant_id DROP NOT NULL;

-- Keyword scopes (§8.2).
ALTER TABLE whatsapp_keyword_actions ADD COLUMN scope VARCHAR(8) NOT NULL DEFAULT 'ROUTED';
ALTER TABLE whatsapp_keyword_actions DROP CONSTRAINT whatsapp_keyword_actions_uniq;
CREATE UNIQUE INDEX whatsapp_keyword_actions_routed_uniq
    ON whatsapp_keyword_actions(sender_id, prefix) WHERE scope = 'ROUTED';
CREATE UNIQUE INDEX whatsapp_keyword_actions_reply_uniq
    ON whatsapp_keyword_actions(sender_id, tenant_id, prefix) WHERE scope = 'REPLY';

-- Templates that may go out on a platform sender (§9.2).
ALTER TABLE whatsapp_templates ADD COLUMN business_label_var INT;   -- e.g. 1 → {{1}} is the business name
```

### 4.3 Domain

`WhatsAppSender.tenant` is **null exactly when the sender is platform-owned**, and
`WhatsAppSender.isPlatform()` says so. The DB check constraint keeps the pairing honest. A
workspace sender behaves exactly as today. Every place that reads a sender's tenant handles a
platform sender explicitly and never falls back to a default:

| Place | Change |
|---|---|
| Webhook `tenantOf` | Becomes `ownerOf`. A platform sender's inbound is stored with `tenant_id NULL`. |
| Metrics | Tagged with `tenant=platform` until routed |
| `SenderSyncJob` | No change: it already sweeps rows, not tenants |
| Creds | Platform account, the same as any sender with no messaging account |

## 5. Platform senders: registration and management

### 5.1 API (platform admin or platform-scope API key only)

These routes sit beside `/v1/admin/whatsapp-accounts`. The lifecycle is the same state machine as
`whatsapp-channel-design.md` §6. Only the path, and who may call it, differ.

```
GET    /v1/admin/platform-senders                                  # list, with assignment counts
POST   /v1/admin/platform-senders                                  # draft: phone, displayName, business profile, locale
GET    /v1/admin/platform-senders/{s}
PUT    /v1/admin/platform-senders/{s}                              # profile, display name, locale, unrouted_reply
POST   /v1/admin/platform-senders/{s}/submit                       # Twilio registration (adopt-before-create, §6.1)
POST   /v1/admin/platform-senders/{s}/verify    { code }
POST   /v1/admin/platform-senders/{s}/sync
POST   /v1/admin/platform-senders/{s}/suspend | /resume
DELETE /v1/admin/platform-senders/{s}                              # revoke; refused while assignments are live

GET    /v1/admin/platform-senders/{s}/assignments
PUT    /v1/admin/platform-senders/{s}/assignments/{tenantId}       { label, dailyOutboundCap }   # assign or edit
POST   /v1/admin/platform-senders/{s}/assignments/{tenantId}/pause | /resume
DELETE /v1/admin/platform-senders/{s}/assignments/{tenantId}[?dryRun=true]                 # end (§11)
```

Service: `PlatformSenderService` wraps the existing `WhatsAppSenderService` lifecycle with
`owner = Platform`. It does not fork the registration code.

### 5.2 Moving an existing number in or out

This extends the V98 transfer with a platform side. The rules are those of
`whatsapp-sender-transfer-design.md`: a new row, history stays with the source, opt-outs are copied,
and webhooks are re-pointed after commit.

| Move | Route | Extra rule |
|---|---|---|
| Workspace → platform | `POST /v1/admin/tenants/{t}/whatsapp-senders/{s}/transfer { "destination": "platform" }` | The source workspace is **not** assigned automatically. A platform admin assigns it afterwards (in practice right away; P4 does both in one runbook step). |
| Platform → workspace | `POST /v1/admin/platform-senders/{s}/transfer { "destinationTenant": "acme" }` | Refused while any assignment is live (`sender_has_assignments`). End them first, so every workspace's links are closed out on purpose. |

### 5.3 Rules

| Rule | Code |
|---|---|
| Only on the platform account. «Connect WhatsApp» is never offered for a platform sender. | `platform_sender_requires_platform_account` |
| Assign only `ACTIVE` or `SUSPENDED` senders | `sender_not_assignable` |
| Never the Playground simulator range | `reserved_phone` |
| The workspace is active | `destination_not_active` |
| Label unique per sender (case-insensitive) | `label_taken` |
| Revoke, or transfer out, only with no live assignments | `sender_has_assignments` |

## 6. Who may do what

| Action | Who |
|---|---|
| Register, verify, edit, suspend, revoke, transfer a platform sender; edit `unrouted_reply` | Platform admin / platform-scope key |
| Assign, pause, resume or end an assignment; change `label` or `daily_outbound_cap` | Platform admin / platform-scope key |
| See a platform sender it is assigned to: phone, approved display name, status, own label, cap, today's sends | The assigned workspace's admins / keys |
| Set `non_member_reply`; register members; keyword actions and its binding on that sender; link its system senders | The assigned workspace, for itself only |
| See other assigned workspaces, their labels, members or traffic | Platform admin only |

The **label is platform-admin-only** because it is what recipients read («*Banca La Suerte*»). A
workspace that could set it could impersonate another workspace on the same number.

## 7. Inbound: the workspace router

### 7.1 Where it runs

The webhook is unchanged except for `ownerOf`. It verifies the signature against the sender row and
stores the inbound with `tenant_id NULL`. On a platform sender, `InboundDispatchWorker` then runs
these number-level steps before any tenant-scoped step:

1. **Sender status gates.** Unchanged.
2. **STOP/START and the opted-out ack** (§11.2). These stay number-level: STOP to the number stops
   that number, whichever business wrote last. This is Meta's model, and the only one a recipient
   can understand.
3. **`SharedInboundRouter.route(sender, phone, raw)`**, which returns one of:
   - `Routed(tenant, reason, member)`: stamp the row (§7.4), then run the normal worker from
     «keyword actions» onwards in that workspace.
   - `Ask(candidates)`: send the pick question (§7.3), hold the message, stop.
   - `NotRouted`: send the non-member reply (§8.3), stop.

### 7.2 Resolution order (owner-agreed 2026-11-23)

**Step A: phone lookup first.** The router reads the phone's memberships on this sender, counting
only live assignments. These are the **candidates**:

- **0 candidates**: the phone belongs to no workspace here. The engine never runs, though the
  message may still route to a workspace for a deterministic answer (step B, signals 1–4).
- **1 or more**: the phone is staff of those workspaces.

**Step B: message signals pick the workspace.** The first match wins.

| # | Signal | Gives | Typical case |
|---|---|---|---|
| 1 | Answer to a pending pick: an `org:<assignmentId>` button or list row, or the typed number of an offered row | that workspace; it becomes the pin | «¿Con cuál negocio?» → «Banca La Suerte» |
| 2 | Bound payload: `act:<id>` → `outreach_actions.tenant_id`; `confirm:`/`cancel:<callId>` → the tenant of the open session that holds that frame | that workspace; re-pins when it is a candidate | Owner taps «Aprobar» on an undo request |
| 3 | Quoted reply (`OriginalRepliedMessageSid` → `outbound_messages.tenant_id`) | that workspace; re-pins when it is a candidate | Messenger swipes to reply on a handoff card; customer taps a ticket template's quick reply |
| 4 | **Routed keyword** (scope `ROUTED`, prefix unique on the number) | the workspace that owns the prefix | Customer sends «TICKET LS 7G4K2» from the route-C QR |
| 5 | Switch keyword (`CAMBIAR NEGOCIO`, `CAMBIAR`, `#negocio`; reserved on platform senders) | `Ask(candidates)` when there are 2+, otherwise the one candidate | Owner of two orgs wants the other one |

**Step C: no signal matched, so resolve from the candidates.**

| Candidates | Result |
|---|---|
| 0 | A **reply-scoped keyword** (§8.2). Its owner must be the one workspace among those that wrote to the phone in the last 7 days whose REPLY prefix matches. Otherwise `NotRouted`. |
| 1 | That workspace |
| 2+, **pinned** | The pin, if the phone's last inbound was within 30 min of now **and** no other candidate has sent to the phone since that inbound |
| 2+, not pinned or pin stale | The one candidate that sent to the phone since the phone's last inbound, if exactly one did. Otherwise `Ask` (most recent sender first); the answer pins (signal 1). |

The pin is `whatsapp_shared_routes.active_tenant_id` with `active_at`. Any routed member message
refreshes it.

Signals 1–4 can route a phone that is not a candidate, for example a customer tapping a ticket copy's
button. Routing only picks the workspace; **membership decides whether the engine may run**
(§7.5).

### 7.3 Asking

- **2–3 candidates**: quick-reply buttons with the labels.
- **4 or more**: a list message, paged with the existing «Más…» (`DefaultInteractiveRenderer`).
- Payloads are `org:<assignmentId>`, never a tenant id.
- Copy: «¿Con cuál negocio quieres hablar?».
- The message that triggered the question is kept in `pending_choice.heldMessageSid`. Once the
  person answers, it is replayed into the chosen workspace, so they never retype it.
- A pick left unanswered for 30 min is dropped, and the next message starts over.

For a phone with 2+ memberships, the first reply after routing to a workspace (or after a switch)
starts with one line: «Estás hablando con *Banca La Suerte*. Escribe *CAMBIAR NEGOCIO* para
cambiar.»

### 7.4 Stamping

`Routed` writes the routed workspace into `inbound_messages.tenant_id`, sets `route_reason`, and
sets `whatsapp_shared_routes.active_tenant_id` when it was a member route. From there the row is an
ordinary inbound of that workspace. Sessions, holds, offered options, the auth trace, usage and the
inspector all read `row.tenantId` unchanged.

Rows that never route (`NotRouted`, `Ask`, STOP/START) keep `tenant_id NULL`. Platform admins see
them on the sender's «Unrouted» tab, and housekeeping trims them like any other inbound.

### 7.5 Engine gate

After routing, the worker continues in the workspace, and that workspace's keyword actions run
first (§8.2). On a platform sender the engine runs only when **all** of these hold:

1. The phone is a member for the routed workspace.
2. The workspace's binding on the sender has an `auth_resolver_id` with `onUnresolved = REJECT`.
   Binding create and update refuse anything else on a platform sender
   (`platform_sender_requires_resolver`), so an anonymous FAQ binding can never answer the public
   there.
3. The resolver accepts. The resolve request carries `"platformSender": true`, so the tenant can
   refuse non-staff even if its member list has drifted.

Anything else never reaches the engine. A non-member, or a phone the resolver rejected, gets the
non-member reply (§8.3) instead of the silent drop REJECT does today. A stale membership therefore
never leaves a person unanswered.

### 7.6 Members API (assigned workspace, for itself)

```
PUT    /v1/admin/tenants/{t}/whatsapp-senders/{s}/members            { "phones": ["+1809…", …] }   # full replace
PUT    /v1/admin/tenants/{t}/whatsapp-senders/{s}/members/{phone}
DELETE /v1/admin/tenants/{t}/whatsapp-senders/{s}/members/{phone}
GET    /v1/admin/tenants/{t}/whatsapp-senders/{s}/members             # own members only
```

- Allowed only while `{t}`'s assignment is `ACTIVE` or `PAUSED`.
- E.164 only; at most 2,000 per workspace; never the sender's own number.
- A member still has to pass the workspace's resolver (§7.5). Registering a phone only claims "this
  person may talk to us here". It does not prove who they are.

### 7.7 Context isolation checklist

On a platform sender, any lookup keyed by `(sender, phone)` without a tenant can leak across
workspaces. Each one must be scoped, or checked to carry no content:

| Reader | Fix |
|---|---|
| `OutreachContextSeeder` (appends the phone's unseen outreach of the last 7 days) | **Filter by routed tenant.** Without this, org B's undo request lands in org A's model context. |
| `channel_sessions.findOpen` | Already takes the tenant; the index change (§4.2) lets each workspace keep its own session |
| 24 h window (`insideServiceWindow(sender, phone)`) | Stays number-level. It is a fact about the number (Meta's rule) and carries no content. |
| `channel_auto_replies` ledger | Number-level throttle on the non-member reply; no content |
| Opt-outs | Number-level by design (§7.1) |
| `OfferedOptionsStore`, holds, Playground client sessions | Already keyed by tenant |

## 8. What a non-member gets

### 8.1 The rule

A non-member never reaches an assistant. They get only deterministic answers: STOP/START, a keyword
action of the workspace the message routed to, or the non-member reply.

### 8.2 Keyword scopes

| Scope | Uniqueness | Matches when | Smart Lottery use |
|---|---|---|---|
| `ROUTED` | Prefix unique on the number | Anyone sends it (signal 4); it routes by itself | `TICKET <tag>` (route C) |
| `REPLY` | Prefix unique per workspace on the number | The message already routed to that workspace (signals 1–3). For a non-member: exactly one workspace with that prefix wrote to the phone in the last 7 days. With 2+, ask. | `NO ES MIO`, `NO MAS TICKETS` |

Existing keyword rows migrate as `ROUTED`, which is exactly today's behaviour. On a workspace sender
both scopes behave as today, since only one tenant is involved.

### 8.3 Non-member reply

- **Attributed to one workspace** (signals 1–3, or exactly one workspace wrote to the phone in the
  last 30 days): that assignment's `non_member_reply`. Example: «Gracias por escribir a *Banca La
  Suerte*. Este número solo envía avisos; para jugar usa …».
- **Not attributable**: the platform sender's `unrouted_reply`. Example: «Este número solo envía
  avisos de los negocios que lo usan. Responde STOP para no recibir más.»
- Throttled to one per phone per 24 h via `channel_auto_replies`.
- The reply never names a workspace that hasn't messaged the phone.

## 9. Outbound on a platform sender

### 9.1 Using it

`WhatsAppSenderService.requireUsable(tenant, sender)` returns the sender in two cases: it is the
tenant's own workspace sender, or a platform sender with an `ACTIVE` assignment to the tenant. It
replaces `requireForTenant` in `OutboundDispatcher`, `WhatsAppOutreachLeg`, the system-sender link
validation (`unknown_whatsapp_sender` otherwise) and the workspace-scoped template / keyword /
binding routes. A `PAUSED` assignment is `sender_inactive` for that workspace only.

- `outbound_messages.tenant_id` and `outreach_deliveries` record the **sending workspace**. Signal 3
  and §8.3 read it from there.
- `GET /v1/admin/tenants/{t}/whatsapp-senders` lists assigned platform senders with
  `owner: "platform"`, the label and the assignment status. Lifecycle routes for them
  (`submit`, `verify`, `revoke`, `transfer`, profile edits) return `403 platform_sender`.

### 9.2 Every message names the business

The approved display name on a platform sender is neutral (e.g. «Avisos Smart Lottery»), so the
business has to be named inside each message.

- **Free-form and in-session messages** (assistant replies, cards, quick replies): the adapter
  prefixes `*{label}*\n`. Always on for platform senders, with no toggle.
- **Templates**: Meta's text is fixed, so the template itself has to carry the name.
  - A template declares `businessLabelVar: n`. **The platform fills `{{n}}` with the assignment
    label**, overwriting whatever the caller sent, so no workspace can put another's name there.
  - On a platform sender, a template without `businessLabelVar` is refused
    (`template_not_shared_safe`) and falls back like `template_not_approved`.
  - On a workspace sender, the variable is filled with the workspace's display name, so one template
    set serves both kinds of sender.

### 9.3 Templates and holders

Unchanged from `whatsapp-shared-templates-design.md`. A platform sender is on the platform account,
so an assigned workspace can send any APPROVED platform template it holds. Assignment grants no
templates.

### 9.4 Shared blast radius

The quality rating, the messaging limit and STOP belong to the **number**, so one careless workspace
degrades it for all of them.

- **Per-assignment cap.** `daily_outbound_cap` counts business-initiated (template) sends per
  rolling 24 h. Above it, the leg falls back as `assignment_cap_reached`. In-session sends don't
  count: they are free and were invited.
- **Per-workspace metrics.** For each `(sender, tenant)`: sends, opt-outs after that workspace's
  send, and blocks/failed deliveries, shown on the assignment row. A platform admin can pause one
  workspace without touching the others.
- **STOP attribution.** A STOP counts against the workspace that sent the phone its last message, so
  the noisy one shows up.

## 10. Smart Lottery integration

### 10.1 What does not change

The workspace stays = org (`tenantId = orgCode`). These work on a platform sender as-is:

- `resolve-staff-identity`: the header/body tenant is the routed workspace.
- `KeywordForwardController`: the platform posts the owning workspace's `tenantId`.
- The outbox `POST /v1/integrations/{tenant}/senders/{senderId}/messages`: it uses the org's own
  system senders, linked to the platform sender.
- Route B ticket copies: they go through the Dueño system sender.

`StaffTenantDirectoryImpl.findFirst…` and `YayaOutreachConfigResolver.forTenant`'s refusal never see
two orgs on one tenant.

### 10.2 Hub (`smart-lottery-customer-admin` + `smartlotteryweb`)

**«Número de WhatsApp» picker** (`yayaops_section.dart:745-805`). It lists the org's own numbers
plus platform senders assigned to its tenant, shown as «+1 502 … · Compartido · solo personal».
Assignments are made in the platform console; the hub only chooses among numbers the tenant can
use.

**S8 on a platform sender** (`YayaOpsWhatsAppProvisioner` / `YayaOpsWhatsAppSenderService.select`):

- Links Dueño + Entregas to the platform sender id. This is the same call as today.
- Registers the `businessLabelVar` template versions (§10.4).
- Converges keyword actions as `ROUTED TICKET <tag>`, `REPLY NO ES MIO` and `REPLY NO MAS TICKETS`.
  Today all three are plain prefixes (`provisioner:52-54`).
- Ensures **one** binding on the sender: `owner_ops_v1` with resolver `sl-staff-resolver`, whose
  profile override already sends messengers to `messenger_ops_v1`, and `onUnresolved=REJECT`.
  - Never a customer-sales binding.
  - `convergeAutoReply` writes the assignment's `non_member_reply` instead of a binding's
    `unresolvedReplyText`.
- Runs a full members sync (§10.3).
- In S6, pushes `whatsappPhoneE164`, `whatsappCompartido=true` and `ticketClaimTag`.

**`ticketClaimTag`.** 2–3 characters, unique among the orgs on that number. The hub derives it from
the org code and retries on `409 keyword_prefix_taken`.

**Status card.** Shows «Compartido», the label, today's sends against the cap and the member count,
plus a «Resincronizar personal» button.

### 10.3 Install: staff member sync (`smart-lottery-shared-apis`)

- **Members**: exactly the users `YayaResolveStaffIdentityService` would accept. That means a
  verified mobile, active, not blocked, and a role that maps to an ops profile (`:118-159`). Extract
  that predicate into one shared method, so the members list and the resolver cannot drift apart.
- **Incremental sync**: user save, role change, block, deactivation and mobile verification each
  enqueue `PUT …/members/{phone}` or `DELETE` on the existing `yaya_outreach_outbox` (new kind
  `WA_MEMBER`, deduped per user).
- **Full sync**: S8 and «Resincronizar personal» send the full replace.
- No periodic job. The resolver check (§7.5) covers drift in between.
- Only when `whatsapp_compartido = 1`.

**Implementation note (SL, 2026-11-24).** The install holds no `/v1/admin` credential, so every
member write goes through the hub.

- **Full sync:** the hub reads the install's `GET /api/internal/yaya-outreach/staff-phones`
  (`X-Internal-Auth`), then does `PUT …/members`.
- **Incremental:** identity publishes `StaffMemberChangedEvent` → after commit, one outbox row of
  kind `WA_MEMBER` per user → hub `POST /internal/yaya-ops/whatsapp-members {codigoOrganizacion,
  addPhone, removePhones}` → `PUT`/`DELETE …/members/{phone}` on the org's linked sender, only
  when that sender is `PLATFORM`.
- **No sender id on the install.** The staff rule both the resolver and the sync use is
  `StaffOpsEligibility`.
- **Staff binding:** S8 creates it with `allowedOverrideProfiles` = `owner_ops_v1`,
  `messenger_ops_v1`.

### 10.4 Templates

Every catalog template gains a leading business-name variable and declares `businessLabelVar: 1`:

> `sl_cash_handoff_closed_v2`: «De *{{1}}* · La oficina confirmó la entrega {{2}} por {{3}}. Quedó cerrada y no requiere más acción.»
>
> Meta refuses a body that starts or ends with a variable, even inside `*…*` («Variables can't be at the start or end of the template», subCode 2388299, first submission 2026-11-24). That is why the opening is «De *{{1}}* · », not «*{{1}}* · ».

- The R5 lint is satisfied: there are words between the variables.
- Each template gets a version bump, so one Meta review per template, done once for the whole
  platform account (holders). Orgs on their own numbers move to the same versions.
- `StaffNotificationRouter` keeps sending its existing variables. The platform fills `{{1}}`, and
  the numbering shifts in the catalog only.
- The ticket copies (`sl_ticket_copy_v2`, `sl_ticket_copy_text_v2`) already named the business in
  their `_v1` wording. Their `_v2` wording drops that second mention, so `TicketSharePublisher`'s
  `_v2` lists start at the ticket number.

### 10.5 Install: route C and customer paths

- **Route C QR text** (`TicketShareMessages`): on a platform sender it becomes
  `TICKET <tag> <code>`. The parser accepts both forms, and ignores the tag on a private number.
  Code uniqueness stays per org (`allocateClaimCode`), because the tag already separates orgs.
- **Customer resolver guard**: `yaya_allowed_senders` must never hold a platform sender's number,
  because the customer resolver would then resolve players on it. The hub refuses the pair and the
  admin form hides it.
- **Org admin app** (`yaya_outreach_settings_page.dart`): on a platform sender it shows «Los
  clientes no pueden conversar por este número; reciben tickets y avisos.»

### 10.6 Migration (install `db/updates`)

```sql
ALTER TABLE configuracion_yaya_outreach
    ADD COLUMN whatsapp_compartido   TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN ticket_claim_tag      VARCHAR(4) NULL;
```

Apply on base and basedl **before** the JAR.

## 11. Ending an assignment

`DELETE /v1/admin/platform-senders/{s}/assignments/{tenantId}[?dryRun=true]` mirrors the transfer's
`effects`. In that workspace it:

- unlinks the system senders (listed in the response),
- deactivates its bindings on the sender,
- deletes its keyword rows and members,
- closes open channel sessions (reason `assignment_ended`),
- drops its parked outbound,
- clears routes whose active workspace it was.

Opt-outs stay, because they belong to the number. The hub sees `no_whatsapp_sender` on the next send,
and «Número de WhatsApp» flags the org as needing a number.

Archiving a workspace ends its assignments with the same effects (FK cascade plus the same cleanup
hook).

## 12. Console (`yayaagenticweb`)

**Platform admin: a new «Platform senders» page**, next to «WhatsApp accounts».

- **Register**: the existing sender wizard (business profile, submit, verification code, review
  state), pointed at `/v1/admin/platform-senders`.
- **Sender card**:
  - status, approved display name and `unrouted_reply`;
  - **«Workspaces»** tab: rows with label, status, cap, today's sends, 7-day opt-outs and member
    count, plus «Assign workspace…» (workspace picker, label, cap), «Pause» and «End…» (dry-run
    preview of effects);
  - **«Unrouted»** tab: inbound that matched no workspace.
- **Moving a number in**: on a workspace sender card, «Move to workspace…» gains a
  «Platform (shared)» destination.

**Assigned workspace.** The platform sender appears in its WhatsApp list with a «Platform · shared»
chip.

| Editable | Read-only | Hidden |
|---|---|---|
| Members (read + resync), keyword actions, its binding, `non_member_reply` | Phone, display name, status, label, cap | Lifecycle actions, other workspaces |

## 13. Risks

| Risk | Mitigation |
|---|---|
| One workspace's spam degrades the number for all | Per-assignment cap, per-workspace metrics, pause (§9.4). An org that needs volume, or customer chat, gets its own workspace sender. |
| A workspace registers another org's staff phone and receives their messages | Only SL backends (tenant keys) write members, and only users with an OTP-verified mobile. Signals 1–3 beat membership. A phone with 2+ memberships is asked and told who it's talking to (§7.3). Audit: member writes in the workspace's log; assignment events. |
| Staff of two orgs send free text that reaches the wrong one | The sticky workspace lasts only 30 min. The router asks when another workspace wrote in between. Taps and quoted replies route exactly. `CAMBIAR NEGOCIO` switches. |
| A customer's STOP silences every org on that number for that phone | Meta's per-number opt-out makes this unavoidable. STOP is attributed to the last sender (§9.4), so the cause is visible. |
| Org A's 24 h window lets org B send free-form | Allowed by Meta. B's message is still labelled, and none of A's content reaches B. |
| Making `tenant_id` nullable breaks code that assumes it | The `SenderOwner` sealed type forces every caller to handle `Platform`. The DB check constraint keeps the pairing honest. Tests cover webhook, worker, sync job and metrics with a platform sender. |
| Cross-workspace leak through a `(sender, phone)` lookup | §7.7 checklist, plus a test per reader showing that two workspaces on one number see nothing of each other |
| Re-review of the `_v2` templates | One review per template per account. Old versions keep working on private numbers until the switch. |

## 14. SPI

```java
/** Picks the workspace an inbound on a platform sender belongs to. */
public interface SharedInboundRouter {
    RouteDecision route(WhatsAppSender sender, String endUserPhone, Map<String, Object> raw);

    sealed interface RouteDecision {
        record Routed(Ids.TenantId tenant, String reason, boolean member) implements RouteDecision {}
        record Ask(List<AssignmentRef> candidates) implements RouteDecision {}
        record NotRouted(Optional<Ids.TenantId> attributedTo) implements RouteDecision {}
    }
}
```

v1 ships `DirectorySharedInboundRouter`, which implements the signals of §7.2 over the members
table. An HTTP membership source could later plug in behind it, but only for signal 6. Signals 1–5
stay platform-owned.

## 15. Stages

| Stage | Repo | Content |
|---|---|---|
| **P1 — Platform senders + assignments + send** | yaya-agentic | V99 schema; `SenderOwner`; `/v1/admin/platform-senders` lifecycle + assignments + events; transfer to/from platform; `requireUsable` in dispatcher / outreach leg / system-sender link / workspace routes; label prefix; `businessLabelVar` fill + `template_not_shared_safe`; per-assignment cap + metrics; end-assignment effects; console «Platform senders» page |
| **P2 — Router** | yaya-agentic | `SharedInboundRouter` + routes table; members API; pick question + replay; switch keyword; first-contact line; engine gate + binding validation; `platformSender` in the resolve request; keyword scopes; non-member / unrouted replies; «Unrouted» tab; §7.7 isolation fixes and tests |
| **P3 — SL** | smart-lottery-v1 | `_v2` templates with `{{1}}`; S8 platform-sender branch + hub picker / status; install migration + S6 fields; member sync on the outbox + shared eligibility predicate; route-C tag; customer-sender guard; org-admin note |
| **P4 — Rollout** | ops | Transfer +15024874573 from `test` to the platform; assign `test` and the first orgs; one-click each; check with a staff phone in two orgs, a route-C scan and a STOP |

P1 is useful on its own: assigned workspaces can send before the router exists. Until P2 ships, their
inbound only gets the platform's unrouted reply.

## 16. Decisions log and open questions

| # | Question | Answer |
|---|---|---|
| 1 | Which number becomes the shared one | **+15024874573** (owner, 2026-11-23). Today it is `test`'s. P4 moves it to the platform and assigns `test` back. fastcash's +18455336210 stays a workspace sender. |
| 2 | Host workspace | **None** (owner, rev.2). Senders can be owned by the platform. |
| 3 | Default `daily_outbound_cap` | **1,000** (owner, 2026-11-23) |
| 4 | Meta Business Verification | Already verified (owner); it does not lift the 2-number cap here |
| 5 | Neutral display name Meta will review for +15024874573 («Avisos Smart Lottery»?) | **Open** |

## 17. Wire contracts (pinned 2026-11-23 for the parallel build)

All JSON uses camelCase. Errors use the platform's usual `{error, message}` shape, with the codes
named here.

### 17.1 Workspace surface (`/v1/admin/tenants/{t}/…`, the workspace's admins/keys)

**`GET …/whatsapp-senders`** lists the workspace's own senders, then every platform sender with a
live (`ACTIVE` or `PAUSED`) assignment to `{t}`. `SenderResponse` gains two fields:

```json
{ "id": "snd_…", "tenantId": null, "owner": "PLATFORM", "phoneE164": "+15024874573",
  "status": "ACTIVE", "approvedDisplayName": "Avisos Smart Lottery", …existing fields…,
  "assignment": { "id": "wsa_…", "label": "Banca La Suerte", "status": "ACTIVE",
                  "dailyOutboundCap": 1000, "nonMemberReply": "…", "templateSends24h": 12,
                  "members": 7 } }
```

- `owner` is `"WORKSPACE"` or `"PLATFORM"`.
- `assignment` is `null` on workspace senders.

**Other routes on an assigned platform sender:**

- `GET …/whatsapp-senders/{s}` works the same way.
- `PUT …/{s}`, `submit`, `verify`, `sync`, `suspend`, `resume`, `revoke`, `transfer` and
  `repoint-webhooks` return **403 `platform_sender`**.
- Any route for a platform sender with no live assignment to `{t}` returns **404 `sender_not_found`**.

**Assignment and members routes:**

```
PUT    …/whatsapp-senders/{s}/assignment        { "nonMemberReply": "…" }        → assignment view (above)
GET    …/whatsapp-senders/{s}/members                                            → { "phones": [..], "count": n }
PUT    …/whatsapp-senders/{s}/members           { "phones": ["+1809…"] }         → { "phones": [..], "count": n }   (full replace)
PUT    …/whatsapp-senders/{s}/members/{phone}                                    → 204
DELETE …/whatsapp-senders/{s}/members/{phone}                                    → 204
```

- The member routes are refused on a workspace sender with **400 `not_a_platform_sender`**.
- Phones must be E.164 (**400 `invalid_phone`**).
- At most 2,000 members (**400 `too_many_members`**).
- `{phone}` in a path is URL-encoded (`%2B1809…`).

**Keyword actions** (`GET|PUT …/{s}/keyword-actions`): each item is
`{prefix, forwardUrl, scope}`, where `scope` is `"ROUTED"` (the default when absent) or `"REPLY"`.

- A PUT replaces only the calling workspace's rows.
- On a platform sender, a `ROUTED` prefix that another workspace holds returns
  **409 `keyword_prefix_taken`**, with `message` naming the prefix and nothing else.
- `CAMBIAR`, `CAMBIAR NEGOCIO`, `#NEGOCIO`, `STOP` and `START` are reserved on platform senders
  (**400 `reserved_keyword`**).

**Templates** (`…/{s}/templates/by-name/{name}` and the account routes): `TemplateDeclaration`
gains `"businessLabelVar": 1`, the 1-based placeholder number that the platform fills with the
business name. The console's create and update routes accept the same field:

- `POST …/templates`: `TemplateRequest.businessLabelVar`.
- `PUT …/templates/{id}`: `TemplateUpdate.businessLabelVar`. Null keeps the stored value; 0 clears
  it.

- On a platform sender: the assignment label.
- On a workspace sender: the workspace's display name.
- Declaring under a workspace path works for an assigned platform sender, with the platform account
  holder rules.

**Channel bindings** with endpoint `WHATSAPP_SENDER` = a platform sender must have `authResolverId`
set and `onUnresolved` = `REJECT`. Anything else returns **400 `platform_sender_requires_resolver`**.
The `profileId` is the workspace's own profile.

**Outreach fallback reasons** (`deliveries[].fallbackReason`), in addition to today's:

- `assignment_cap_reached`
- `template_not_shared_safe`
- `sender_inactive`, which now also covers a paused assignment

### 17.2 Calls the platform makes to the tenant

- **Auth resolver request body**: adds `"platformSender": true` when the inbound came in on a
  platform sender. The field is absent otherwise.
- **Keyword forward body**: unchanged. `tenantId` is the workspace that owns the matched keyword
  row.

### 17.3 Platform-admin surface (`/v1/admin/platform-senders`, platform admin or platform-scope key)

Routes are as in §5.1. Request and response bodies:

- **Create/update.** `POST /` takes `{phoneE164, displayName, businessProfile, defaultLocale,
  unroutedReply}`. `PUT /{s}` takes the same fields minus `phoneE164`.
- **`submit` / `verify`.** The same bodies as the workspace routes.
- **Sender response.** `PlatformSenderResponse` = `SenderResponse` (with `owner: "PLATFORM"`,
  `tenantId: null`) plus `unroutedReply` and `assignmentCount`.
- **Assign/edit.** `PUT /{s}/assignments/{tenantId}` takes `{label, dailyOutboundCap?}`.
  Errors: **409 `label_taken`**, **400 `destination_not_active`**, **400 `sender_not_assignable`**.
- **Assignment response.**
  `{id, senderId, tenantId, label, status, dailyOutboundCap, nonMemberReply, assignedBy, assignedAt, endedAt, stats: {templateSends24h, optOuts7d, members}}`.
- **End.** `DELETE /{s}/assignments/{tenantId}[?dryRun=true]` returns
  `{dryRun, effects: {systemSendersUnlinked: [..], bindingsDeactivated, keywordActionsDeleted, membersDeleted, sessionsClosed, parkedOutboundDropped, routesCleared}}`.
- **Unrouted.** `GET /{s}/unrouted?limit=50` returns
  `[{messageSid, phoneMasked, receivedAt, reason, body}]`.
- **Transfer.**
  - Workspace → platform: `POST /v1/admin/tenants/{t}/whatsapp-senders/{s}/transfer {"destinationPlatform": true, "copyOptOuts": true}`.
  - Platform → workspace: `POST /v1/admin/platform-senders/{s}/transfer {destinationTenant, profileId?, profileVersion?, copyOptOuts?}`.
    Returns **409 `sender_has_assignments`** while assignments are live.
- **Revoke.** `DELETE /{s}`. Returns **409 `sender_has_assignments`** while assignments are live.

## 18. Implementation notes (2026-11-24)

What the code does where the sections above left room:

- **Owner.** `WhatsAppSender.tenant()` is null for a platform sender (`isPlatform()`), and every
  `(tenant, id)` lifecycle method of `WhatsAppSenderService` takes `tenant = null` to mean "the
  platform sender". `requireUsable(tenant, id)` (own, or a live assignment) is what outbound,
  templates, keyword actions, bindings and the system-sender link now call.
  `isAssignmentActive` turns a `PAUSED` assignment into `sender_inactive` on every send path.
- **Router** (`channel/whatsapp/shared/DirectorySharedInboundRouter`).
  - Only `ACTIVE` assignments count, both as candidates and as the workspace a signal may name.
  - Candidates keep assignment order, so the question always lists the businesses the same way.
  - A `confirm:`/`cancel:` button is routed by signal 3: Twilio sends `OriginalRepliedMessageSid`
    with every button tap.
  - "Since the last inbound" is the phone's previous inbound row on the number.
  - `route_reason` values: `pick`, `action`, `quoted_reply`, `routed_keyword`, `switch`,
    `reply_keyword`, `member`, `pinned`, `last_writer`.
- **Worker** (`InboundDispatchWorker`, steps 1r and 1m).
  - Routing runs after STOP/START. An opted-out phone is never asked anything.
  - The question is sent as the platform's own line (no tenant, no label). Its answer is filed
    with `org_choice_answer`, and a held message is re-run in the chosen workspace.
  - On a platform sender, `no_binding_matched` and `auth_resolver_rejected` also send the
    non-member reply, throttled like every other one.
- **Labels.** `WhatsAppSenderService.labelled` prefixes `*label*\n` once.
  - Free-form replies, in-session cards and quick replies all go through it.
  - Templates fill `businessLabelVar` from the assignment label, or from the workspace's display
    name on its own number. The caller's variable list fills the other placeholders in order, so
    integrations keep sending the list they send today.
- **Cap.** Counts `outreach_deliveries` rows with a `template_name` and no fallback over the last
  24 h for `(tenant, sender)`.
- **STOP attribution.** `channel_opt_outs.attributed_tenant_id` is the workspace of the latest
  outbound to that phone on the number within 30 days.
- **Archive.** Both archive paths publish `WorkspaceArchivedEvent`.
  `SenderAssignmentService.onWorkspaceArchived` ends that workspace's assignments with the §11
  effects.
- **Tests.**
  - `PlatformSenderE2ETest` (15): admin surfaces, members, keyword scopes, binding rule, every
    router path through the real webhook, transfers both ways, template label var, STOP
    attribution, labels, archive.
  - `WhatsAppOutreachLegTest` (+6): label fill, cap, paused, `template_not_shared_safe`.
  - `SystemSenderWhatsAppLinkTest` (+1).
