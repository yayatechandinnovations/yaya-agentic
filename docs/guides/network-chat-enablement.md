# Enabling the Corillo network chat (`player_network_v1`)

This runbook turns on the network-chat runtime
(`docs/yaya-agentic-network-chat-runtime-design.md`). There are two ways to enable
it — the **admin console** (recommended, no restart) and **env vars** (for
headless deploys). Both end at the same runtime.

## What "enabled" gives you

- The `player_network_v1` profile (banca reads + personal reads always on; play/
  claim behind their own toggles).
- An auto-provisioned **chat entry point** (front door) — no hand-wiring, no auth
  resolver. Players authenticate with the normal chat **phone verification**; the
  verified phone is what the tools act on.
- Play/claim as **live toggles** — flip them in the console and they take effect on
  the next turn (no restart, no re-seed).

## Prerequisite (not in this repo)

The tools call the **Smart Lottery hub** at `/internal/agent/*`. That service must
be deployed and reachable at the **hub base URL** you configure, authenticated with
the runtime's **`X-Agent-Auth`** credential. Until then, reads return a
"service unavailable" message and play can't broker a credential. For local
development, use the **built-in stub** (below) instead of the real hub.

## Option A — admin console (recommended)

1. Open the admin console → **Inbound channels → Network chat**.
2. Set the **Hub base URL** and **Credential ref** (the name of the env var holding
   the `X-Agent-Auth` secret; the literal value is accepted in dev).
3. Toggle **Enabled** on and **Save**. This seeds the profile, provisions + publishes
   the chat entry point, and shows the **chat handle / URL**.
4. Flip **Play** and/or **Claim** when the platform enablement chain is green. These
   are live — no restart.

Everything is per tenant, guarded by the ADMIN role. Disabling **Enabled** pauses
the front door (settings are kept).

## Option B — env vars (headless)

Set on the backend and restart:

| Env var | Meaning |
|---|---|
| `YAYA_NETWORK_SEED_TENANT` | Tenant id to seed `player_network_v1` at startup. |
| `YAYA_NETWORK_HUB_BASE_URL` | Smart Lottery hub base URL. |
| `YAYA_NETWORK_HUB_CREDENTIAL_REF` | Env var name holding the `X-Agent-Auth` secret. |
| `YAYA_NETWORK_PLAY_ENABLED` | `true` to expose play tools (default `false`). |
| `YAYA_NETWORK_CLAIM_ENABLED` | `true` to expose the claim tool (default `false`). |

A DB row (Option A) overrides these; anything the row leaves unset falls back to
them. The env path does **not** auto-provision an entry point — create one bound to
`player_network_v1` in the console (or use Option A once, which provisions it).

## Local end-to-end without the real hub

1. Start the backend with `SPRING_PROFILES_ACTIVE=local` and
   `YAYA_NETWORK_STUB_ENABLED=true`. The stub serves `/internal/agent/*` (and the
   install play surface) with canned bancas, prizes, quotes, etc.
2. In the console (Option A), set **Hub base URL** to this server
   (`http://localhost:8080` by default) and enable the feature + play + claim.
3. Open the chat at the shown handle, verify a phone, and drive:
   *"¿en cuál banca juego?"* → picker → pick a banca → quote → *"Sí"* → confirmed.

The stub is gated OFF by default and can never wire up in production.

## What each toggle does

| Toggle | Effect |
|---|---|
| **Enabled** | Master switch: seeds the profile + provisions/pauses the front door. |
| **Play** | Shows/hides `play_quote` / `play_confirm` / `banca_link_confirm` (live). |
| **Claim** | Shows/hides `prize_claim_network` (live). |

Play/claim tools that are off are never offered to the model and are refused at
dispatch — the toggle is a real gate, not a UI hint.
