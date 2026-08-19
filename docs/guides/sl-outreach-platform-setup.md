# Smart Lottery Outreach (YayaOps Bridge) — platform-side setup runbook

Companion to the SL-repo guide
`smart-lottery-v1/docs/yaya-agentic-proactive-channel-ops-e2e-guide.md`,
which covers the install/SL side. **This runbook covers everything that must
be configured on the yaya-agentic platform** for an SL org's proactive
cash-handoff notifications to work end to end.

Audience: platform operator (you). Date: 2026-08-19.

---

## 0. What runs where

| Thing | Where |
|---|---|
| Platform runtime (backend :8080 → host 127.0.0.1:18080) | `root@smartlottery.do:5522` → `/opt/yayaagentic`, `deploy/compose.prod.yaml`, service `backend` |
| Operator console + admin API | `https://yayaagentic.smartlottery.do` (`/v1/admin/**`) |
| End-user chat SPA (where staff talk to the agent) | `https://yayaagenticchat.smartlottery.do` |
| Ingest endpoint SL posts to | `POST /v1/integrations/{tenantId}/senders/{senderId}/messages` |
| Deploy script | `deploy/deploy.sh` (rsyncs code, **never overwrites the operator's `/opt/yayaagentic/.env`**) |

The platform pieces the bridge rides on (System Messaging SM-0→SM-3, channel
auth resolvers, resolver profile override) are **already shipped** — no
platform code change is needed for a new SL tenant. Everything below is
configuration.

> **ONE-CLICK UPDATE (2026-08-19):** once the one-click provisioner ships
> (smart-lottery `docs/yayaops-one-click-provisioning-design.md`), §1–§6 of
> this runbook collapse into the **"Provisionar YayaOps"** button on the hub's
> Asistente IA page. The platform-side prerequisites shrink to a ONE-TIME
> deployment setup:
> 1. Deploy a yaya-agentic build ≥ V82 (service-token admin API +
>    platform-scope keys — Flyway V81/V82 ride the JAR).
> 2. As a **platform-admin user**, mint ONE API key with `platformScope: true`
>    (`POST /v1/admin/workspaces/{ws}/api-keys` body
>    `{"name":"sl-hub","role":"ADMIN","platformScope":true}`) and set it on
>    the hub as `SL_YAYA_AGENTIC_PLATFORM_KEY`.
> 3. Ensure `yaya.agentic.secrets.encryption-key` is set (the service-token
>    admin API 503s without it).
> Per-tenant, everything — tenant, catalogs, resolver, entry points, senders,
> service-token secret, install config — is then done by the button. The
> `.env` tenant-secrets map (§6) becomes legacy fallback: migrate existing
> tenants into the DB rows and empty the map. §7 (staff onboarding) stays
> human. The sections below remain valid as the manual path and for
> troubleshooting what the button automates.

---

## 1. Per-tenant checklist (do these in order)

For each SL org going live (tenant id = the org code, e.g. `sl`, `basedl`;
**one tenant per member org** on shared pools):

1. ☐ Workspace/tenant exists on the platform, `host_base_url` set (§2)
2. ☐ Workspace API key registered in the hub's "Asistente IA" page (§3)
3. ☐ `messenger-ops` + `owner-ops` catalogs installed for the tenant (§4)
4. ☐ Outreach provision run from the hub — creates jwt-secret, resolver,
   entry points, senders on this platform (§5)
5. ☐ **Manual, platform-side only:** service-token secret added to
   `yaya.agentic.auth.service-token.tenant-secrets` (§6)
6. ☐ Staff onboarded in yaya chat with verified phones (§7)
7. ☐ Smoke-verified (§8)

Steps 2–4 are the same admin-plane setup any SL AI tenant already gets; if
the org already runs `customer-sales` chat, only steps 3(re-run with the ops
templates), 4, 5, 6 are new.

## 2. Tenant workspace + host_base_url

Create/verify the workspace in the console (tenant id **must equal the org
code** — the hub composes admin paths as `/v1/admin/tenants/{orgCode}/…`).

On the workspace "General" tab set `host_base_url` to the install's public
FQDN. This is load-bearing twice: the staff auth-resolver's `httpPath` is
composed onto it, and HTTP tool dispatch is structurally pinned to it.

> **Shared-pool member orgs:** `host_base_url` must be the **POOL BASE
> install's FQDN** (the shared container), NOT a member vanity domain —
> otherwise resolver + tool calls never reach the container that actually
> serves the member org.

## 3. Workspace API key (hub → platform admin auth)

The hub (customer-admin) drives all provisioning through the platform admin
API with a workspace-pinned API key (`docs/guides/api-keys-usage.md`):

- Console: workspace → **Settings → API keys → Create key** (role `ADMIN`).
- Copy the `yk_live_…` secret once, register it in the hub UI:
  **Asistente IA** page for that (installation, org).

Without it, the hub's provision endpoints fail fast with
`yaya_workspace_key_missing`.

## 4. Install the ops catalogs (profiles + tools)

The `messenger_ops_v1` / `owner_ops_v1` profiles and their tools (including
the updated `handoff_reject` with required `proposedAmount`, 0-as-none) live
in the SL repo (`smart-lottery-customer-admin/src/main/resources/yaya-agentic-catalog/{messenger-ops,owner-ops}/`)
and are **pushed into this platform** by the hub:

```
POST {hub}/api/customer-admin/installations/{installationId}/ai/orgs/{orgCode}/provision
{ "templateIds": ["messenger-ops", "owner-ops"], ... }
```

Idempotent; re-run it after any catalog change (drift is visible via the
hub's `…/ai/orgs/{org}/drift` endpoint, keyed on `catalog_hash`).

⚠️ Deploy gate: the hub must be running a customer-admin JAR that contains
the updated catalogs (the `proposedAmount` tool change is deploy-pending as
of 2026-08-19) — provisioning pushes whatever the *running* JAR bundles.

## 5. Outreach provision (creates the tenant's messaging plumbing here)

Run from the hub (dry-run first):

```
POST {hub}/api/customer-admin/installations/{installationId}/ai/orgs/{orgCode}/outreach/provision
{ "dryRun": true, "resolverSecret": "<the install's IDENTITY_YAYA_INBOUND_SECRET>" }
```

On apply it upserts, on THIS platform, under `/v1/admin/tenants/{orgCode}/…`:

| Resource | What |
|---|---|
| `jwt-secrets` | HS256 sender secret, `iss = sl-outreach-<tenant>` (generated if blank). Authenticates SL's ingest posts (scope `system:inbound:send`). |
| `auth-resolvers` | `sl-staff-resolver` — kind HTTP, `httpPath /api/identity/yaya/resolve-staff-identity`, `serviceAuthKind SIGNED_JWT`, `onRefreshFailure REJECT`, ACTIVE. Turns a chat caller's phone into a real SL staff JWT + profile. |
| `entry-points` ×2 | messenger + owner flavors — AUTH_REQUIRED, ResolverExchange, `requiresVerifiedPhone`, published. |
| `system-senders` ×2 | "from" channels bound to those entry points — LLM_MEDIATED, ByPrincipal, `system.reply` callback. **Their platform-generated IDs are the `messengerSenderId` / `ownerSenderId`** the install config needs (they are channels, not people — see `smart-lottery-v1/docs/yaya-outreach-sender-ids-and-phone-matching-report.md`). |

The response returns `installEnv` (single-org installs) and
`installOrgConfig` (ready-to-PUT per-org body for shared pools) — those are
consumed on the SL side per its e2e guide §3.

## 6. Service-token secret — the ONLY hand-edited platform config

The `system.reply` callback (platform → SL `/integrations/callbacks/{ref}`)
authenticates with an HMAC service token. There is **no admin API** for its
secret map; it lives in Spring config:

```
yaya.agentic.auth.service-token.tenant-secrets   # Map<tenantId, secret> — HMAC key, not a bearer
```

`application.yml` ships it empty and there is no dedicated env hook, so on
the prod box set it through `SPRING_APPLICATION_JSON` in
`/opt/yayaagentic/.env` (the compose `backend` service loads `.env` via
`env_file`). Keep **all tenants in the one JSON value** — this is a
whole-map override, so merge, never clobber:

```bash
# /opt/yayaagentic/.env  (chmod 600)
SPRING_APPLICATION_JSON={"yaya":{"agentic":{"auth":{"service-token":{"tenant-secrets":{"sl":"<secret-sl>","basedl":"<secret-basedl>"}}}}}}
```

(Plain lowercase-alphanumeric tenant ids could also use relaxed env binding
`YAYA_AGENTIC_AUTH_SERVICETOKEN_TENANTSECRETS_SL=…`, but ids with dashes
can't be expressed that way — the JSON form is the recommended, unambiguous
one. Don't mix both.)

Each tenant's secret must equal what the install stores as
`serviceTokenSecret` (per-org config row) or `SL_YAYA_OUTREACH_SERVICE_TOKEN_SECRET`
(single-org env). Generate per tenant: `openssl rand -hex 32`.

**Applying it requires recreating the backend container**
(`docker compose -f deploy/compose.prod.yaml up -d backend` from
`/opt/yayaagentic`) — restart is operator-controlled; schedule it, don't
bounce it casually (it drops live chat sessions' in-flight requests;
Redis-backed session state itself survives).

While in `.env`, verify `YAYA_AUTH_ALLOW_ANONYMOUS=false` — the compose
default is `true` (dev). Check the current value before changing anything;
flipping it affects every surface on the deployment.

## 7. Staff onboarding (why ingest may 404)

Outreach delivers to the principal `chat:+E164`. That principal only exists
after the staff member has **opened `yayaagenticchat.smartlottery.do` and
verified their phone** (the same phone their SL user / channel-preference
row carries). Until then, SL's ingest posts for that person return an
**opaque 404**, which SL's outbox treats as terminal → dead-letter. Onboard
messengers first, then enable the org on the SL side.

Reply-side match is also phone-based: chat reply → `sl-staff-resolver` →
SL identity resolves the caller's phone to an ACTIVE staff user, mints their
real JWT and picks the profile by role (`MESSENGER → messenger_ops_v1`,
admin-grade → `owner_ops_v1`, others rejected). Guardrail to remember: the
override profile must be **published** in the tenant and allowed by the
entry point (empty allowlist = any published) — an unpublished
`messenger_ops_v1` silently falls back / rejects per `on_override_denied`.

## 8. Verify

Platform side:
- Console → tenant workspace: resolver ACTIVE, 2 entry points published,
  2 senders present; ids match the install's config row.
- `auth_resolver_calls` audit table — one row per reply resolution;
  `ok_staff_messenger_ops_v1` outcomes mean the loop closed.
- Sender-JWT ingest check: a well-formed SL post answers 202 (or 409 on
  idempotent replay — SL counts that as delivered). 401 → jwt-secret
  iss/secret mismatch; 404 → recipient not onboarded (§7).

SL side (from its e2e guide): outbox rows leave `PENDING`; `DEAD_LETTER`
with 404 = onboarding gap, with 401 = secret mismatch.

## 9. Failure cheat sheet

| Symptom | Likely cause | Where to look |
|---|---|---|
| Ingest 401 | sender JWT iss/secret not in tenant `jwt-secrets`, or wrong tenantId in path | §5 provision output vs install config |
| Ingest 404 (dead-letter on SL) | recipient phone not onboarded in chat | §7 |
| Reply gets FAQ/anonymous behavior | entry point missing `requiresVerifiedPhone`/resolver binding, or resolver INACTIVE | console + `auth_resolver_calls` |
| Resolver dispatch never reaches the install | tenant `host_base_url` wrong (pool members: must be pool BASE FQDN) | §2 |
| `system.reply` callback 401 on SL | service-token secret mismatch or backend not recreated after `.env` edit | §6 |
| Provision 409 `yaya_workspace_key_missing` | no API key registered on the hub for that (installation, org) | §3 |
| Tools missing / old `handoff_reject` schema in chat | catalogs not (re)provisioned, or hub running the old JAR | §4 |
