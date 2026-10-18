# Pa'l Patio tenant — platform-side setup

> **Audience:** operators of yaya-agentic. Pa'l Patio (the US→DR box and drum
> shipping platform) uses yaya-agentic as its WhatsApp assistant: one tenant
> per shipping company, tools that call the Pa'l Patio backend, an identity
> resolver that turns a WhatsApp phone into a Pa'l Patio customer, and
> proactive messages (pickup reminders, change-request decisions, waitlist
> offers) pushed through system messaging. The Pa'l Patio side provisions all
> of this through the admin API from its `tool/dev.sh agent-provision
> <company-slug>` command; this page lists what lands here and the two things
> that remain hand-set.
>
> **Companions:** `twilio-whatsapp-setup.md` (the Twilio account),
> `auth-resolver-runbook.md` (identity resolvers), `sl-outreach-platform-setup.md`
> (the same system-messaging shape, for Smart Lottery).

---

## 1. Deployment settings (once per deployment)

| Setting | Value | Why |
|---|---|---|
| `ANTHROPIC_CHAT_MODEL` | `claude-opus-5` | Pa'l Patio runs text understanding on Claude Opus 5. The env var binds `spring.ai.anthropic.chat.options.model`; the platform default for other deployments stays unchanged. |
| `yaya.agentic.secrets.encryption-key` | set | Required by the per-tenant JWT secrets and the service-token admin API. |
| `yaya.agentic.auth.service-token.tenant-secrets` | one entry per Pa'l Patio company (see §3) | HMAC key for `X-Yaya-Service-Token` in both directions. |

Inbound **images** are accepted by default on every WhatsApp sender (the
customer sends a photo of a box; the model sees it and the Pa'l Patio
`quote_from_photo` tool receives its URL). To refuse images on a sender, set
`metadata.acceptInboundImages = false` on that sender. Caps:
`yaya.agentic.channels.whatsapp.images.max-per-message` (default 4) and
`…images.max-bytes` (default 5 MiB).

Spanish opt-out words (`PARAR`, `BAJA`, `SALIR`, `CANCELAR SUSCRIPCIÓN`;
`EMPEZAR`, `INICIAR`, `REANUDAR`) are built in; the confirmation copy follows
the sender's `defaultLocale`, so Pa'l Patio senders should be created with
`defaultLocale = es-DO`. A bare `cancelar` is never an opt-out (it is how a
customer cancels a pickup).

## 2. What Pa'l Patio provisions per company (through `/v1/admin/**`)

One **secret per company** is generated on the Pa'l Patio side and used for
everything below; Pa'l Patio stores it encrypted.

| Resource | Shape | Notes |
|---|---|---|
| Tenant | `POST /v1/admin/tenants` `{id: "<company-slug>", displayName, hostBaseUrl: "<Pa'l Patio API base>", inboundOriginAllowlist}` | Tenant id = the company slug. |
| JWT secret | `POST /v1/admin/tenants/{id}/jwt-secrets` `{iss: "palpatio-<company-slug>", secret: <the company secret>, audiences: ["yaya-agentic"]}` | Verifies the customer JWTs the resolver returns (HS256, `sub` = Pa'l Patio customer id, 1 h). |
| Auth resolver | kind `HTTP`, `httpPath: /webhooks/yaya-agentic/resolve-identity`, `serviceAuthKind: SIGNED_JWT` (secret = the company secret, `aud` = tenant id), `onRefreshFailure: REJECT` | Pa'l Patio answers `200 {jwt}` for a known customer phone, `404` otherwise. |
| Channel binding | the company's WhatsApp sender → profile `palpatio-customer`, resolver attached, `on_unresolved: ANONYMOUS` | Unknown numbers can still quote; `register_customer` turns them into customers. |
| Tools | `ToolHandlerRef.Http`, path-only `urlTemplate` under `/api/v1/agent/**`, `AuthForwarding.SERVICE_TOKEN` | The token's `subject` is the engine principal subject: the customer id, or `whatsapp:+E164` for anonymous callers. Pa'l Patio verifies the HMAC with the company secret. Confirmable tools (`create_booking`, `reschedule`, `cancel`, `add_item`, `change_payment_choice`) carry Spanish confirm copy. |
| Profile + personality | `palpatio-customer`, Spanish, Dominican usage | From `backend/tool/agent/profile-customer.json` in the Pa'l Patio repo. |
| System sender | `POST /v1/admin/tenants/{id}/system-senders` LLM-mediated, `ByPrincipal` addressing, `system.reply` callback | Pa'l Patio posts proactive messages to `POST /v1/integrations/{tenant}/senders/{sender}/messages` with `recipient: {kind: ByPrincipal, subject: <customer id>}`, `actions[]`, `dedupKey`, headers `X-Yaya-Service-Token` (scope `system:inbound:send`), `X-Nonce`, `X-Timestamp`. |

## 3. Hand-set: the service-token secret map

As for every host integration, the HMAC key behind `X-Yaya-Service-Token` is
not writable through the admin API. Add the company's secret to the map (merge,
never clobber; see `sl-outreach-platform-setup.md` §6 for the env form):

```json
{"yaya":{"agentic":{"auth":{"service-token":{"tenant-secrets":{"<company-slug>":"<the company secret>"}}}}}}
```

Then recreate the backend container. Rotating the secret means rotating it on
the Pa'l Patio side first (`tool/dev.sh agent-rotate <company-slug>`), then
here, then "Invalidate cached tokens" on the resolver.

## 4. Contract details the Pa'l Patio side matches

- Service token: `tenantId.subject.expEpochSeconds.scopesCsv.base64url(HMAC-SHA256(secret, tenantId.subject.exp.scopesCsv))`, header `X-Yaya-Service-Token`, 60 s lifetime on tool calls.
- Resolver request: `POST {hostBaseUrl}/webhooks/yaya-agentic/resolve-identity`, JSON body with the caller phone, headers `X-Yaya-Tenant`, `X-Yaya-Request-Id`, `X-Yaya-Idempotency-Key`, `Authorization: Bearer <HS256 JWT, 30 s, aud = tenant id>`.
- Inbound photos reach a tool as `UserMessage.attachments.images[] = {url, contentType, sizeBytes}` (and the model as `chat.images[] = {contentType, base64}`); the model passes the `url` into the tool's `mediaUrl` argument. Twilio media URLs expire after about 24 h and require the sender's Twilio Basic auth to fetch.
