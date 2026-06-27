# Step-up authentication — operator runbook

Companion to `docs/design/whatsapp-channel-auth-design.md`. Covers the parts
of the step-up system operators actually touch: configuring tenants for OTP /
SMS / deep-link, rotating secrets, debugging via the audit table, and a
cheat-sheet for the failure modes you'll see in production.

If you're investigating a step-up issue right now, jump to §5.

---

## 1. Architecture in 60 seconds

The step-up runtime sits between the `Authorizer` chain and the
`ConversationEngine`. When any `Authorizer` returns `AuthzDecision.DenyOfferStepUp(hint)`:

1. `StepUpCoordinator.handleDeny` looks up a registered `StepUpProvider` for
   `hint.method` (OtpEmail / OtpSmsSecondary / DeepLink / Custom).
2. The provider mints + ships an OTP code (via tenant's secondary email/SMS)
   or returns a one-time signed URL (DeepLink) and the coordinator persists a
   row in `step_up_pending`.
3. The engine emits ONE Token to the user ("Check your email for a code"),
   marks the in-flight tool result `DENIED` with `step_up: true`, and ends the
   turn. The LLM does not get a continuation round.
4. For OTP, the next inbound from the same session is intercepted by the
   `InboundDispatchWorker` BEFORE the engine sees it: if the body matches
   `\d{6}`, it's routed to `coordinator.verifyOtp`. For DeepLink, the tenant's
   web app POSTs the JWT back to `/v1/channels/whatsapp/step-up/callback`.
5. On verification success, `channel_session_elevation` gets a row with the
   audience's scope (`stepup:<audience>`). The `StepUpScopeAuthorizer` merges
   that scope into the principal on the very next tool call, the original
   action authorizes, and the bot completes it.

Persistence tables: `step_up_pending`, `step_up_audit`, `channel_session_elevation`.
Tokens, codes, and link bytes NEVER land in `audit_authz`, `turns`, recorder
payloads, or any non-step-up audit table — that invariant is policed by
`StepUpSecuritySweepTest`.

---

## 2. Configuring a tenant for step-up

Step-up is **opt-in per tenant and per audience**. Three pieces of config:

### 2.1 Rules — what triggers step-up

Add a rule under `yaya.agentic.auth.step-up.rules` in `application.yaml`:

```yaml
yaya:
  agentic:
    auth:
      step-up:
        rules:
          - tenant: acme
            guarded-scopes: [tool:transfer_funds, tool:wire_money]
            audience: money_movement
            method: OtpEmail              # OtpEmail | OtpSmsSecondary | DeepLink | Custom:<id>
            elevation-ttl: PT30M
            user-safe-reason: "Verify it's you before moving money."
```

Restart the app — `StepUpScopeAuthorizer` reads these once at startup.
A request for any tool whose `requires.scopes` overlaps with `guarded-scopes`
will demand `stepup:<audience>` and emit `DenyOfferStepUp` if missing.

### 2.2 Secondary comms — where OTPs are delivered

OTP codes are delivered ON A DIFFERENT CHANNEL than the one we're elevating
(rule 1 of the security non-negotiables). For each tenant, configure where:

**Admin UI:** Tenant → General → Secondary communications.

**API:** `PUT /v1/admin/tenants/{tenantId}/secondary-comms`

```json
{
  "emailRelay": {
    "fromAddress": "noreply@acme.example",
    "providerId":  "platform-smtp"
  },
  "smsBackup": {
    "fromNumber": "+15550100123",
    "providerId": "twilio-sms"
  }
}
```

`emailRelay` is needed for `OtpEmail`; `smsBackup` is needed for
`OtpSmsSecondary`. A rule that depends on a missing channel raises a
`failed:no_secondary_comms` audit row on first use — fix the tenant config and
the next attempt works.

### 2.3 Principal claims — where to ship to

Step-up needs to know where to send the OTP for THIS user. The provider reads
the principal's claims:

- **OtpEmail** → `email` claim
- **OtpSmsSecondary** → `phone_secondary` claim (NOT the WhatsApp phone)
- **DeepLink** → no per-user claim; the URL is the delivery medium
- **Custom** → whatever the tenant's provider impl reads

For `LookupPrincipal` bindings, your `PhoneToPrincipalResolver` populates
these claims when it resolves the inbound phone to a tenant-side user.

### 2.4 Deep-link secret — global, not per-tenant

DeepLink uses HS256 with a single platform-owned secret. Set once at deploy:

```yaml
yaya:
  agentic:
    auth:
      step-up:
        deep-link:
          secret: <base64 of >= 32 random bytes>
          landing-url: https://acme.example/step-up
          jwt-ttl: PT5M
```

The SAME secret authenticates the tenant's web-app callback POST. Share it
with the tenant ops team out-of-band; they hold it like any other API key.

---

## 3. Rotating the deep-link secret

```text
1. Generate a new secret:  openssl rand -base64 32
2. Update yaya.agentic.auth.step-up.deep-link.secret in your config store
3. Update the same secret in the tenant's web-app env
4. Rolling-restart yaya-agentic
5. In-flight pending rows die — that's fine; users retry
```

Active elevations are NOT invalidated by secret rotation (they live in
`channel_session_elevation` keyed by sessionId, no JWT). Rotation only blocks
NEW step-up cycles until both sides re-sync.

---

## 4. Inspecting an in-flight cycle

```sql
-- All in-flight pending rows
SELECT id, session_id, audience, method, attempts, expires_at
FROM   step_up_pending
WHERE  completion IS NULL
ORDER BY started_at DESC;

-- Audit timeline for a single session
SELECT method, outcome, target_address, started_at, correlation_ulid
FROM   step_up_audit
WHERE  tenant_id = '<tenant>'
  AND  session_id = '<sess>'
ORDER BY started_at DESC;

-- Active elevations
SELECT session_id, elevated_subject, audience, expires_at
FROM   channel_session_elevation
WHERE  expires_at > NOW();
```

The Flutter admin console exposes the same data under the session viewer's
"Step-up" tab (when present).

---

## 5. Failure-mode cheat sheet

| Audit `outcome`             | Meaning                                                                  | Fix                                                                                       |
|------------------------------|--------------------------------------------------------------------------|-------------------------------------------------------------------------------------------|
| `started`                    | Provider minted + shipped the artifact; awaiting verification.           | None — wait for the user.                                                                 |
| `verified`                   | Cycle succeeded; `channel_session_elevation` row written.                | None.                                                                                     |
| `expired`                    | Pending row hit `expires_at` without a verify attempt.                   | Tell the user to retry the original action — a fresh cycle will mint a fresh code.        |
| `failed:code_mismatch`       | User typed the wrong code (or pasted an old one).                        | None — user retries within the attempts budget.                                           |
| `failed:malformed_code`      | User typed text that didn't look like 6 digits.                          | Bot re-prompts; usually a UX-not-engineering issue.                                       |
| `failed:exhausted`           | User burned all `max_attempts` (default 5) without success.              | Tell the user to restart the original action.                                             |
| `failed:cool_off`            | Session-level cool-off engaged (3 failures in 10 min).                   | Wait 10 minutes for cool-off to clear.                                                    |
| `failed:no_provider`         | Rule says `method=X` but no provider bean is registered.                 | Check the spelling against `OtpEmail` / `OtpSmsSecondary` / `DeepLink` / `Custom:<id>`.   |
| `failed:start:no_secondary_comms` | Provider start() couldn't find emailRelay / smsBackup config.       | PUT the tenant's secondary-comms config (§2.2).                                           |
| `failed:start:no_phone_secondary` | Principal has no `phone_secondary` claim for `OtpSmsSecondary`.     | Update the `PhoneToPrincipalResolver` to populate the claim, or use `OtpEmail` instead.   |
| `aborted:superseded`         | A new cycle for (session, audience) replaced this one.                   | None — informational.                                                                     |
| `verified:playground_pre_elevated` | Operator stamped an elevation via the playground pre-elevation knob.| None — informational.                                                                     |

---

## 6. Common questions

**Q: My LLM hangs after step-up instead of completing the original action.**
A: The LLM doesn't remember the original tool call across the elevation gap.
After verifying, the user has to ask again. This is by design — re-prompting
gives them an out if they no longer want to proceed.

**Q: The OTP is going to the user's WhatsApp number.**
A: It shouldn't — `OtpEmail` ships to the `email` claim, and
`OtpSmsSecondary` rejects if `smsBackup.fromNumber` matches the WhatsApp
sender. If you're seeing this, file a security issue.

**Q: Can I gate step-up on something other than scopes?**
A: Not via `StepUpScopeAuthorizer`. Write your own `Authorizer` that emits
`AuthzDecision.DenyOfferStepUp(...)` and add it to the chain.

**Q: How do I disable step-up entirely for a tenant?**
A: Remove the tenant from `yaya.agentic.auth.step-up.rules`. The bot loses
no functionality — it just stops asking for verification.
