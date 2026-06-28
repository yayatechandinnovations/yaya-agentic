# Using API keys

API keys let scripts, apps, and CI call the yaya-agentic **operator/admin API**
(`/v1/admin/**`) without a human's email + password. A key is bound to one
workspace and a role. Full design: `docs/design/api-keys-design.md`.

> Operator plane only. API keys do **not** grant access to the conversational
> plane (`/v1/sessions/**`) — apps that chat *as a user* still mint per-tenant
> JWTs.

## 1. Create a key

In the console: **Settings → API keys → Create key** (needs `OWNER`/`ADMIN`).
Pick a name, a role (≤ your own), and an optional expiry.

Or via an interactive session:

```bash
curl -X POST https://api.example.com/v1/admin/workspaces/<workspace>/api-keys \
     -H "Content-Type: application/json" \
     -H "X-XSRF-TOKEN: <token>" --cookie "YAYA_SESSION=...; XSRF-TOKEN=..." \
     -d '{"name":"ci-deploy","role":"MEMBER","expiresAt":null}'
```

The response contains the secret **exactly once**:

```json
{ "id":"...", "name":"ci-deploy", "keyPrefix":"yk_live_abc",
  "role":"MEMBER", "secret":"yk_live_abc12345xyz9q7w3e8r2t5y6u1i4o0p7a3" }
```

Copy `secret` into your secret store now — it is never shown or recoverable
again. The server only keeps a hash.

## 2. Make requests

Send the secret as a bearer token. Nothing else is needed (no cookie, no CSRF):

```bash
curl -H "Authorization: Bearer yk_live_abc12345xyz9q7w3e8r2t5y6u1i4o0p7a3" \
     https://api.example.com/v1/admin/profiles
```

The key is **pinned to its workspace**, so the workspace is implied. You may
still pass `?tenant=<workspace>` — but it must match the key's workspace or you
get `404`.

```bash
# list tools in the key's workspace
curl -H "Authorization: Bearer $YK" https://api.example.com/v1/admin/tools

# create a profile (write — needs a MEMBER+ key)
curl -X POST https://api.example.com/v1/admin/profiles \
     -H "Authorization: Bearer $YK" -H "Content-Type: application/json" \
     -d '{"id":"retail","displayName":"Retail","introOneLiner":"...","systemPromptFragment":"..."}'
```

Rules of thumb:
- **Read** endpoints need `VIEWER`+, **writes** need `MEMBER`+, workspace
  administration needs `ADMIN`+. The key's role is the ceiling.
- Bearer requests are CSRF-exempt and stateless — no `X-XSRF-TOKEN`, no session.
- Use HTTPS. Plaintext bearer is rejected in production.

## 3. What keys cannot do

Forbidden regardless of role (use an interactive human session instead):

- Mint other API keys (`POST .../api-keys`)
- Delete/archive a workspace
- `/v1/auth/me`, `/v1/auth/logout` (session endpoints)
- Operator-auth strategy config

## 4. Rotate & revoke

There is no rotate endpoint — **create new → roll your app over → revoke old**.
Revoke from **Settings → API keys → Revoke** or:

```bash
curl -X DELETE https://api.example.com/v1/admin/workspaces/<workspace>/api-keys/<keyId> \
     -H "Authorization: Bearer $ADMIN_YK"
```

Revocation takes effect within ~1s (cluster-wide) and is immediate on the node
that handled it. A revoked, expired, or wrong-workspace key returns `401`/`404`.

## 5. Error reference

| Status | Meaning |
|---|---|
| `401` | unknown/revoked/expired/malformed key, or plaintext bearer in prod |
| `400` | key in a query string (`?api_key=`), or a session-only endpoint |
| `403` | key role too low, or a key-forbidden endpoint (e.g. minting keys) |
| `404` | wrong workspace, or key/resource not in the key's workspace |
| `409` | workspace hit its active-key cap |
| `429` | too many failed bearer attempts from your IP |

Bodies are intentionally generic (`{"error":"...","message":"..."}`); a wrong
prefix is indistinguishable from a wrong secret.
