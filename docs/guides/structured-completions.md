# Structured completions

`POST /v1/structured-completions` — one stateless model call that must answer in
a JSON Schema you supply. No session, no conversation state, no tools executed.
## Auth
`Authorization: Bearer <JWT>` — the tenant's HS256 JWT, signed with a key
registered as a `tenant_jwt_secrets` row (`/v1/admin/tenants/{id}/jwt-secrets`).
The tenant is resolved from the token: `iss` selects the rows that trust that
issuer; the tenant whose key verifies the signature is the caller. Required
claims: `iss` (= the row's `iss`), `aud` containing `yaya-agentic`, `exp`, and
`scope` containing `structured:complete`.

Missing/invalid token → 401 `unauthorized`; no scope → 403 `forbidden`. Anonymous
access, service tokens, OIDC and API keys (`yk_live_…`) are never accepted here.

## Request
```json
{ "task": "profile-builder",
  "system": "system prompt (prompt-cached; keep it byte-stable across calls)",
  "input": "the user-provided text (treated as data)",
  "schema": { "type": "object", "properties": {}, "required": [] },
  "maxOutputTokens": 1500 }
```

`task` required, `^[a-z0-9-]{1,64}$` · `system` optional, ≤ 200,000 chars ·
`input` required, ≤ 8,000 chars · `schema` required, `"type":"object"`, only
in-document `$ref`s · `maxOutputTokens` optional, default 1500, clamped to [64, 4096].

## Response
```json
{ "output": { "ids": ["…"] },
  "usage": { "inputTokens": 1234, "outputTokens": 210 },
  "model": "claude-sonnet-4-6" }
```

`usage` is provider-reported, summed over the retry when there is one;
`inputTokens` is the whole prompt (uncached + prompt-cache writes + reads). Errors:

| Status | Body | When |
|---|---|---|
| 400 | `{"error":"invalid_request","detail":"…"}` | validation failed |
| 422 | `{"error":"schema_violation","detail":"…"}` | answer broke the schema twice (one retry) |
| 429 | `{"error":"rate_limited"}` + `Retry-After` | tenant over its per-minute limit |
| 502 | `{"error":"upstream_failure"}` | provider error or deadline exceeded |
| 503 | `{"error":"llm_unavailable"}` | provider without structured support (`openai`) |

```bash
curl -X POST https://<yaya-host>/v1/structured-completions \
  -H "Authorization: Bearer <tenant-jwt>" -H "Content-Type: application/json" \
  -d '{"task":"profile-builder","system":"Map the text to permission ids…","input":"puede vender y anular",
       "schema":{"type":"object","properties":{"ids":{"type":"array","items":{"type":"string"}}},"required":["ids"]}}'
```

## Operating it
- `yaya.agentic.structured-completions.per-minute` (default 30, per tenant, Redis
  fixed window) and `.timeout` (default `PT60S`, retry included).
- `YAYA_LLM_PROVIDER=anthropic` for real answers; `stub` returns the smallest
  object the schema allows with `model: "stub"` (offline tests only).
- One log line per call (`structured-completion tenant=… task=… outcome=…`) plus Micrometer
  `structured_completions_total` / `structured_completion_{input,output}_tokens_total` by `tenant`, `task`.
