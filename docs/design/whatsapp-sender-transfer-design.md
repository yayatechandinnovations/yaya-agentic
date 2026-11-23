# WhatsApp sender transfer — move a number from one workspace to another

> Companion to `whatsapp-channel-design.md` §7 (Twilio account), `whatsapp-shared-templates-design.md`
> §4 (who may hold a template), `docs/guides/whatsapp-sender-registration.md` (the two registration
> paths) and V92 (re-registration after revoke).

| | |
|---|---|
| **Status** | 🟢 Implemented 2026-11-22 (T1–T3) |
| **Owner** | Yenfri Herrera |
| **Last updated** | 2026-11-22 |
| **Scope** | Let an operator take a live WhatsApp sender out of one workspace and put it in another, keeping the same Twilio sender, approved display name and phone, with no new OTP and no new Meta review. |
| **Out of scope** | Senders registered through «Connect WhatsApp» (their Twilio subaccount and WABA belong to the source workspace — §9). SMS senders (§10). Moving conversation history. |

---

## 1. The problem

A phone number can be live in exactly one workspace: `whatsapp_senders_live_phone_uniq` (V92) is
`UNIQUE (phone_e164) WHERE status <> 'REVOKED'`. The only way to free a number is **Revoke**, and
revoke does two things (`WhatsAppSenderService.revoke`):

1. `DELETE /v2/Channels/Senders/{sid}` at Twilio — the sender is destroyed on the BSP side.
2. Flip the row to `REVOKED`.

So "reuse the number in workspace B" today means: revoke in A, then run the whole registration in
B — new sender at Twilio, new WhatsApp code by SMS/voice, the display name back through Meta review,
and a gap of hours to days with the number offline. For a number that was already approved and
working, that is all waste: nothing about the number changed, only which workspace answers it.

There is also a hole in the current behavior. If the Twilio `DELETE` fails, revoke still flips the
row (best effort). The Twilio sender survives, and the next workspace that types that phone into
«Start sender registration» **adopts** it (`TwilioMessagingChannelProvider.adoptSender`). Adopting
re-points the webhooks and skips verification. Nothing proves that workspace has any right to the
number. A "release, then claim" feature built on top of this would turn that hole into the main path.

## 2. Goals

1. **One operation, both sides.** The number leaves A and lands in B in the same request. It is never
   claimable by a third workspace in between.
2. **Same Twilio sender.** Same SID, approved display name, business profile and quality rating. No
   OTP, no review, no downtime beyond the webhook re-point.
3. **No history crosses workspaces.** A's inbound messages, sessions, deliveries and keyword actions
   stay in A. B starts clean.
4. **Never message someone who opted out of this number** just because it changed workspaces.
5. **Recoverable.** If Twilio is unreachable halfway through, no inbound message is lost and the
   transfer finishes on its own.

## 3. Decision: transfer, not release-and-claim

| Option | Why not / why |
|---|---|
| **Release + claim** — A releases without the Twilio delete, B registers the phone and adopts it | Between the two steps the number belongs to whoever types it first (§1). It would need a claim code or a named destination to be safe, and at that point it is a transfer with extra steps. |
| **Re-key the row** — `UPDATE whatsapp_senders SET tenant_id = B` | Everything keyed by `sender_id` would follow it to B: `inbound_messages`, `channel_sessions`, `outreach_deliveries`, `whatsapp_keyword_actions`, the auto-reply ledger, and every lookup that derives the tenant from the sender (`findTenantForSenderId`). That is A's customer conversation history landing in B. |
| ✅ **Transfer** — retire A's row and create a new row in B for the same Twilio sender, in one transaction, then re-point Twilio's webhooks at the new row | The new row has its own id, webhook URL and secret. A's history stays on A's retired row. The partial unique index allows exactly one live row, so the swap is atomic. |

## 4. What moves and what stays

| Item | Keyed by | Transfer behavior |
|---|---|---|
| Phone, Twilio SID, display name, approved display name, business profile, logo, default locale | sender row | **Copied** to B's new row |
| Status | sender row | **Copied** (`ACTIVE` stays `ACTIVE`, `SUSPENDED` stays `SUSPENDED`) |
| Sender id, webhook URL, webhook secret | sender row | **New** in B. The old id becomes a forwarding tombstone (§6.3). |
| Opt-outs (`whatsapp_opt_outs`) | `(sender_id, end_user_phone)` | **Copied** by default (goal 4). The end user sees a number, not a workspace. |
| Inbound / outbound history, `channel_sessions`, `outreach_deliveries` | `sender_id` | **Stay in A.** Open channel sessions on A's row are closed with reason `sender_transferred`. |
| Parked outbound (`outbound_pending`) and retry rows for A's row | `sender_id` | **Dropped** and counted in the response. They were written by A's assistant. |
| Channel bindings (routes to an assistant) | `endpoint_id` | **A's are deactivated.** B gets one `MatchAny` binding if the request names a `profileId`. Otherwise the sender lands in B with no route, and the console prompts the operator to add one. |
| Keyword actions, auto-reply policy, `start_flow_id` | sender / tenant | **Not copied.** They point at A's flows, tools and URLs, which mean nothing in B. |
| System senders linked to it (`system_senders.whatsapp_sender_id`) | A's system senders | **Unlinked** (set null), and listed in the response so A's operator knows which integrations lost their WhatsApp leg. |
| Templates (platform account) | `account_key = 'platform'` + holders | **Unchanged.** Approval is per account, so B can use any platform template it holds. The response lists the templates A held that B doesn't, and a platform admin shares them through the existing holders endpoint. No automatic grant. |
| 24h customer-service window | latest inbound for `(sender_id, phone)` | **Resets** in B. B's first outbound to an existing contact needs a template until that contact writes again. Copying inbound rows to keep the window open would copy history (goal 3). |

## 5. Who may transfer

**Platform admins only** (decided 2026-11-22). Moving a number changes which business answers it,
so it stays a platform operation even when one operator is ADMIN of both workspaces. Workspace-scoped
API keys are refused; platform-scope keys pass the same check a platform admin does.

Refused outright (`forbidden` for anyone but a platform admin):

| Code | When |
|---|---|
| `sender_not_transferable` | The source is `DRAFT`, `SUBMITTING`, `PENDING_REVIEW`, `FAILED` or `REVOKED`. Without a live Twilio sender there is nothing to keep. Revoke it and register in B. |
| `transfer_unsupported_account` | `messaging_account_id` is set (Connect WhatsApp). See §9. |
| `same_workspace` | Destination = source |
| `destination_not_active` | Destination is suspended or archived |
| `reserved_phone` | Playground simulator range `+155501…` |
| `unknown_profile` | `profileId` doesn't exist in the destination |

## 6. Flow

### 6.1 API

```
POST /v1/admin/tenants/{tenantId}/whatsapp-senders/{senderId}/transfer[?dryRun=true]
{
  "destinationTenant": "acme",
  "profileId": "retail-customer",      // optional — creates B's MatchAny binding
  "profileVersion": 3,                 // optional
  "copyOptOuts": true                  // default true
}

200 {
  "transferId": "wst_…",
  "source":      { …SenderResponse, status: "REVOKED" },
  "destination": { …SenderResponse },          // absent on dryRun
  "webhookRepoint": "DONE" | "PENDING",
  "effects": {
    "optOutsCopied": 41,
    "openSessionsClosed": 3,
    "parkedOutboundDropped": 1,
    "bindingsDeactivated": 1,
    "systemSendersUnlinked": ["pos-main"],
    "templatesNotHeldByDestination": ["order_ready", "otp_code"]
  },
  "warnings": [ "the destination has no route for this sender — add one or inbound gets the unresolved auto-reply" ]
}
```

`dryRun=true` runs every check and computes `effects` without writing. The console uses it as the
confirmation screen, the same plan-then-apply shape as «Migrate absolute URLs → path».

### 6.2 Steps

```
1. Authorize (§5).
2. DB transaction (validation re-runs inside it):
     a. close A's OPEN channel_sessions, drop A's parked outbound, deactivate A's bindings,
        unlink A's system senders
     b. source row → REVOKED, flushed before the insert (the partial unique indexes allow one
        live row per phone and BSP sid, and Hibernate would otherwise order the insert first)
     c. insert B's row: copied fields (§4), the status A had while live, fresh id + webhook secret,
        same twilio_sender_sid; source.transferred_to_sender_id = new id
     d. copy opt-outs (if requested), create B's binding (if profileId)
     e. insert whatsapp_sender_transfers row, repoint_status = PENDING — the audit trail
3. After commit: Twilio POST /v2/Channels/Senders/{sid} { webhook: B's callback + status URLs }
     success → repoint_status = DONE
     failure → stays PENDING; SenderSyncJob retries (§6.4)

A concurrent transfer of the same sender loses on the unique index and gets `transfer_conflict` (409).
B's sender carries `metadata.transfer = {transferId, fromTenant, fromSenderId, at, webhooks}`, which is
how the console shows where a number came from and whether the re-point has landed.
```

There is **no Twilio `DELETE`** anywhere on this path. The DB writes come first because the opposite
order fails worse: if Twilio is re-pointed to a new id and then the transaction rolls back, Twilio
delivers to an id that doesn't exist.

### 6.3 The tombstone forwards

Between step 2 and the re-point landing, and for any webhook Twilio already queued or is retrying,
Twilio still posts to `…/{oldSenderId}`. Today that lands on a `REVOKED` row, and
`InboundDispatchWorker` marks it `sender_not_accepting_inbound`, so the message is lost.

The fix: in `WhatsAppTwilioWebhookController`, verify the signature against the old row as today.
Then, if the row is `REVOKED` with a `transferred_to_sender_id`, persist the inbound under the
**live successor** row and tenant, following a chain A→B→C to its live end (at most 8 hops). Both
rows share the same Twilio account, so the signature check is the same one. Status callbacks need
no forward: they update outbound rows by `MessageSid`, which already works through a revoked row.

Revoking a transferred row (it is already `REVOKED`) changes nothing and never calls the BSP, since
the BSP sender now belongs to the destination.

The forward stays in place indefinitely. It costs one indexed lookup, and Twilio has nothing else
it could post to that id.

### 6.4 Re-point retry

`SenderSyncJob` already ticks over in-flight senders (every 5 min). Each tick it also retries every
`whatsapp_sender_transfers` row with `repoint_status = PENDING`, and marks one `FAILED` once it is
over 24 h old. The console shows «Still finishing the move» on B's sender while it is `PENDING` or
`FAILED`, and a platform admin can «Retry move» (`POST …/{senderId}/repoint-webhooks`) at any time. Because of §6.3, a `PENDING` or `FAILED` row loses nothing. It only means inbound takes the
forwarding path.

## 7. Data model — V98

```sql
-- The successor a transferred sender's webhooks forward to (§6.3).
ALTER TABLE whatsapp_senders
    ADD COLUMN transferred_to_sender_id VARCHAR(64) REFERENCES whatsapp_senders(id);

-- One row per transfer: the audit trail and the re-point retry queue (§6.4).
-- No FK to tenants: the trail outlives an archived workspace.
CREATE TABLE whatsapp_sender_transfers (
    id                  VARCHAR(64)  PRIMARY KEY,            -- wst_…
    phone_e164          VARCHAR(20)  NOT NULL,
    twilio_sender_sid   VARCHAR(64)  NOT NULL,
    source_tenant_id    VARCHAR(64)  NOT NULL,
    source_sender_id    VARCHAR(64)  NOT NULL,
    dest_tenant_id      VARCHAR(64)  NOT NULL,
    dest_sender_id      VARCHAR(64)  NOT NULL,
    effects_json        JSONB        NOT NULL,
    repoint_status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    repoint_attempts    INT          NOT NULL DEFAULT 0,
    repoint_last_error  TEXT,
    requested_by        VARCHAR(128) NOT NULL,                -- operator subject or api key id
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    repointed_at        TIMESTAMPTZ,
    CHECK (repoint_status IN ('PENDING','DONE','FAILED'))
);
CREATE INDEX whatsapp_sender_transfers_pending_idx
    ON whatsapp_sender_transfers(created_at) WHERE repoint_status = 'PENDING';
CREATE INDEX whatsapp_sender_transfers_phone_idx
    ON whatsapp_sender_transfers(phone_e164, created_at DESC);
```

`HeadlessRunsMigrationTest` pins the migration target and has to move to 98.

## 8. Code changes

| Where | Change |
|---|---|
| `MessagingChannelProvider` | New default method `repointWebhooks(SenderRef, webhookUrl, statusCallbackUrl, creds)`, the same shape as `verifySender`. The Twilio implementation reuses the webhook `POST` that `adoptSender` already makes. |
| `WhatsAppSenderService` | `transfer(source, senderId, TransferRequest, actor, dryRun)` covers §6.2 steps 1–2. Re-point runs after commit (`@TransactionalEventListener(AFTER_COMMIT)` or explicit), with no row lock held during the remote call, the same reasoning as `revoke`. Extract the webhook-URL building out of `buildRegistrationRequest`. |
| `WhatsAppSenderController` | `POST …/{senderId}/transfer`, run with `Mono.fromCallable(...).subscribeOn(boundedElastic())` because it blocks on Twilio. |
| `WhatsAppTwilioWebhookController` | Tombstone forwarding (§6.3) for inbound and status callbacks |
| `SenderSyncJob` | Drain the `PENDING` re-points (§6.4) |
| `OptOutService` | `copyAll(fromSender, toSender)` |
| Flutter `whatsapp_senders_screen.dart` | «Move to workspace…» in the sender row's actions, shown for `ACTIVE`/`SUSPENDED` platform-account senders. The dialog has a destination dropdown (only workspaces the operator is ADMIN of, or all of them for a platform admin), an assistant dropdown that loads that workspace's profiles, and a «Keep opt-outs» checkbox (on by default). It calls `dryRun` and shows the effects as the confirmation step, then applies. On a `PENDING`/`FAILED` re-point it shows a chip and a Retry button. |

## 9. Connect WhatsApp senders (not in this version)

A sender with `messaging_account_id` speaks through a Twilio subaccount that belongs to the source
workspace (`whatsapp_messaging_accounts.tenant_id`, `UNIQUE (waba_id)`). Moving only the sender
would leave B sending through A's subaccount and A's WABA. That breaks template ownership
(shared-templates §4.1: only the owning workspace may hold a `wma_…` template) and webhook signing.
Doing it right means moving the whole messaging account and every sender under it. That is a
different operation, and production has no such account today (shared-templates §1).
`transfer_unsupported_account` refuses these senders until one exists.

## 10. Related changes

1. **Close the adopt hole (§1), in the same milestone.** A revoke whose BSP delete didn't happen
   (Twilio refused or was unreachable, or no credentials were configured) now records
   `metadata.bspDeleteFailed = true` on the revoked row. Creating a sender for that phone in a
   *different* workspace — or changing a draft to it — is refused with `phone_held_elsewhere`, since
   registering would adopt the still-live BSP sender without a WhatsApp code. Revoking the revoked
   row again retries the BSP delete and clears the flag (console: «Finish removal»). A number that
   was never on the platform, such as the Self Sign-up first number, still adopts as today. Rows
   revoked before V98 carry no flag and are treated as deleted, which is what they were before.
2. **SMS has the same problem, and worse.** `sms_senders_phone_uniq` (V44) is a full `UNIQUE`, so
   a revoked SMS number can never be registered again *anywhere*. V98 replaces it with a V92-style
   partial index, and the create-time check looks only at live senders. A transfer of the same shape
   can come after.

## 11. Milestones

| | Deliverable | Acceptance |
|---|---|---|
| **T1** ✅ | V98, `transfer` + dry run, tombstone forwarding, re-point after commit + retry, adopt guard (§10.1) | WireMock tests cover: transfer of `ACTIVE` sender; each refusal code; inbound posted to old id before re-point lands in B; Twilio 5xx on re-point → `PENDING` → job completes it; opted-out user in A is still suppressed in B; A's history is not readable from B; adopt of a SID revoked in another workspace is refused — `WhatsAppSenderTransferTest` (`transfer_unsupported_account` and `reserved_phone` are not covered by a test) |
| **T2** ✅ | Flutter «Move to workspace…» dialog + re-point chip | Widget test (`flutter test --platform chrome`): dry-run effects render, apply sends what was previewed, re-point retry, «Finish removal» — `whatsapp_senders_screen_test.dart` |
| **T3** ✅ | SMS partial unique index (§10.2) | A revoked SMS number re-registers — `SmsSenderReregistrationTest` |

## 12. Open questions

1. ~~Who may transfer~~ — platform admins only (§5).
2. ~~Opt-outs default~~ — copied by default; the request can turn it off (`copyOptOuts: false`).
3. ~~Two-party offer/accept~~ — deferred until asked for; a platform admin covers it.
4. **Business profile after the move.** The new workspace may be a different brand. Editing the
   profile (or photo) of a live sender isn't supported yet. The transfer keeps the profile as it is.
