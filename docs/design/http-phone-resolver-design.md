# HTTP Phone-to-Principal Resolver (v1)

> 🚫 **SUPERSEDED — 2026-06-27** — by
> [`channel-auth-resolver-design.md`](./channel-auth-resolver-design.md).
> The replacement collapses the `PhoneToPrincipalResolver` + `LookupPrincipal`
> + materializer pipeline into a single `AuthResolver` SPI that returns a
> tenant-signed JWT (verified by the existing per-tenant trust roots) and
> moves resolver configuration from yaml to a per-tenant DB-backed table
> with admin CRUD. Kept here as historical record. Do not implement.
>
> ---
>
> Companion to `whatsapp-channel-auth-design.md` §5.2 (the `PhoneToPrincipalResolver`
> SPI shipped in W-A2) and §15 open question #3 ("Should LookupPrincipal accept
> an HTTP-tool-shaped resolver?"). This doc is the answer.
>
> Touches `yaya-agentic-design.md` §5.6 (profile resolver chain) by adding a
> third source of `profileId` — the lookup endpoint's response — alongside the
> binding's static profile and the existing identity-profile-mappings table.

| | |
|---|---|
| **Status** | 🟡 Proposed |
| **Scope** | A platform-shipped `HttpPhoneToPrincipalResolver` that calls a tenant-owned webhook with the inbound phone, sender, and channel metadata, and accepts back a `Principal`-shaped JSON response — including an optional **`profileId` that selects which profile this conversation runs**. Tenants who consume yaya-agentic AS A SERVICE write the webhook in any language; the platform handles delivery, auth, caching, retries, and the security boundary. |
| **Out of scope (v1)** | Per-tenant resolver write API (the resolver list is yaml-configured for now; admin CRUD lands in v2). Custom request enrichment (e.g. forwarding inbound media URLs). End-user account linking (still post-v1 per the channel design). Multi-region failover URLs. mTLS auth (TLS bearer/HMAC only). |

---

## 1. The one-paragraph summary

`PhoneToPrincipalResolver` (W-A2) is the SPI that turns a verified WhatsApp phone into a tenant-side `Principal`. Today it ships as a Java SPI — tenants either consume yaya-agentic as a library and write a bean, or use the YAML-driven `StaticPhoneToPrincipalResolver` for demos. **Most tenants consume yaya-agentic as a service** and can't ship Java; for them, `LookupPrincipal` is unreachable. v1 of this design adds an HTTP-transport implementation of the same SPI. Tenants ship a small webhook in any language, configure its URL + auth + (optional) HMAC secret in `application.yaml`, and the binding's `LookupPrincipal(resolverId="acme-customer-lookup")` calls that endpoint to resolve every inbound. The wire contract is intentionally narrow — `tenantId`, `senderId`, `e164Phone`, optional channel metadata in → `subject`, `scopes`, `claims`, optional `profileId` back. The optional `profileId` is the architectural lever that closes the loop: the same lookup that says "this phone is user u_42" can also say "u_42 talks to the `retail-vip` profile, not the binding's default `retail-customer`." A single binding now routes to a dynamic per-user profile without requiring multiple bindings + routing rules.

---

## 2. Why a Java SPI alone isn't enough

The `PhoneToPrincipalResolver` SPI is well-shaped for a tenant who runs yaya-agentic in their own JVM and has direct DB access. That tenant writes:

```java
@Component
public class MyJdbcResolver implements PhoneToPrincipalResolver {
    @Override public String id() { return "my-jdbc"; }
    @Override public Optional<Principal> resolve(...) { /* JDBC lookup */ }
}
```

That's a beautiful 30 lines of code and exactly the right level of control for the tenant who needs it.

**But the predominant deployment model is yaya-agentic as a multi-tenant service.** In that model:

- The tenant doesn't run yaya-agentic — the platform does, on behalf of many tenants.
- The tenant has no Java module to ship.
- The tenant DOES have a backend in some language with their user table.
- The bridge between "their backend" and "yaya-agentic" needs to be a network call.

For those tenants, the gap is the binding `LookupPrincipal` form: it requires a resolver bean. There's no other choice, so they default to `PhoneAsPrincipal` (no per-user identity), which limits their bot to FAQ-shaped flows.

This design closes that gap by shipping ONE bean — `HttpPhoneToPrincipalResolver` — that loads N tenant configs at boot. Each config exposes itself as a distinct resolver id in the admin dropdown and the binding's `LookupPrincipal.resolverId` field. The tenant just writes the webhook.

---

## 3. The non-negotiables this design preserves

These come from `whatsapp-channel-auth-design.md` §3 and §8. Every one survives the HTTP transport:

1. **Channel-reserved claims still get stamped.** The materializer's reserved-claim list (`phone`, `channel.sender`, `channel.kind`, `locale`, `twilioContactSid`) wins over any webhook-returned value. A misbehaving webhook can't spoof the channel identity.
2. **Resolver output is validated.** A webhook that returns claims containing reserved keys gets its response rejected, same as a Java bean would.
3. **The webhook is the tenant trust root for identity.** Same posture as `OidcAuthenticator` or `DelegatedHostAuthenticator`: yaya-agentic trusts what the webhook says about WHO the user is. The webhook is the operator's responsibility to secure.
4. **No webhook call ever logs PII.** Request body is logged at DEBUG only, response body never — the response carries the claims that flow into the prompt projection.
5. **Failures fall back per `denyOnUnknown`.** Timeout, 4xx, 5xx, malformed JSON — all behave the same as "Optional.empty() from a Java bean": elevate to AuthenticationException or fall back to Anonymous per the binding's flag.
6. **The webhook is `LookupPrincipal`-scoped, not a general-purpose tool.** It never sees inbound message text. It only sees identity metadata (phone, sender, locale). This bound the blast radius if the webhook is compromised — it can return a wrong Principal, but it can't read the user's message.

---

## 4. The wire contract

### 4.1 Request

```
POST {url}
Content-Type: application/json
Authorization: Bearer <token>            ← when authKind = BEARER
X-Yaya-Service-Token: <signed-tuple>     ← when authKind = SERVICE_TOKEN_HMAC
X-Yaya-Resolver-Id: acme-customer-lookup ← always; helps the webhook log which config invoked it
X-Yaya-Trace-Id: <ulid>                  ← propagates trace through the lookup hop
```

Body:

```json
{
  "tenantId":     "acme",
  "senderId":     "snd_acme_main",
  "e164Phone":    "+15551234567",
  "channelKind":  "whatsapp",
  "channelLocale": "es-DO",
  "twilioContactSid": "MGabc123def456"
}
```

`channelLocale` and `twilioContactSid` are nullable; absent fields ARE omitted from the JSON rather than sent as `null`, so the webhook's parser stays simple. `e164Phone` always starts with `+` (the authenticator rejects malformed phones before this point).

### 4.2 Response — known user

```json
{
  "found":       true,
  "subject":     "u_42",
  "scopes":      ["order:read", "shipment:read"],
  "claims": {
    "email":       "alice@acme.example",
    "displayName": "Alice",
    "tier":        "gold"
  },
  "profileId":      "retail-vip",
  "profileVersion": 3
}
```

- `found: true` is mandatory when the user is known. The webhook MAY also use HTTP 200 with `found: false` or HTTP 404 to signal unknown — both are accepted.
- `subject` is the tenant-side user id. It becomes the Principal's `subject` (after the channel materializer stamps reserved claims on top).
- `scopes` and `claims` flow through unchanged — same merge rules as the Java SPI.
- **`profileId` is optional.** When present, the inbound message is routed to that profile instead of the binding's static default. Tenants without per-user routing simply omit it.
- **`profileVersion` is optional.** When omitted, the most recent ACTIVE version is used.

### 4.3 Response — unknown user

Either:

```json
{ "found": false }
```

… or HTTP 404 with any body. Both are interpreted identically by the resolver. The binding's `denyOnUnknown` flag then decides whether to deny the inbound or fall back to `Anonymous`.

### 4.4 Response — error

Any 5xx, any timeout, any malformed-JSON response, any 2xx with a missing `subject` when `found: true` — all are treated as resolver failures. The cycle proceeds as if the resolver returned `Optional.empty()`, which means the binding's `denyOnUnknown` again decides.

**There's no retry budget at the resolver layer.** The registry's 5-min cache absorbs repeat traffic; an individual call either succeeds in one shot or fails. Retrying would only delay the failure and double our blast on a struggling tenant backend.

---

## 5. Profile selection — the new architectural lever

### 5.1 The current flow (without HTTP lookup)

```
1. Inbound message arrives (Twilio webhook)
2. WhatsAppPhoneAuthenticator → mints Principal from headers
3. Worker calls ChannelBindingService.findFor(tenant, sender, principal)
4. Binding's RoutingRule decides which binding (if multiple)
5. Worker uses binding.profile() — the binding's STATIC profile id
6. engine.start(profile, principal, ...)
```

The binding's static profile is what every inbound on that (sender, routing) tuple talks to.

### 5.2 The new flow (with HTTP lookup returning profileId)

```
1. Inbound message arrives
2. WhatsAppPhoneAuthenticator → calls HTTP resolver →
   Principal now carries _profile_id and _profile_version reserved claims
3. Worker calls ChannelBindingService.findFor(...) — unchanged
4. Worker checks principal.claims for the reserved keys:
     - present  + valid → uses (profileId, profileVersion) from claims
     - absent or invalid → falls back to binding.profile()
5. engine.start(resolved profile, principal, ...)
```

The HTTP webhook becomes the **per-user** profile router. The binding still owns the **per-sender** default; the webhook overrides PER USER.

### 5.3 Reserved claim keys

Two new reserved channel claims are added to `PrincipalPolicyMaterializer.RESERVED_CLAIM_KEYS`:

- `_profile_id`     — the per-user profile id from the HTTP response
- `_profile_version` — the per-user profile version; optional

The leading underscore distinguishes "platform-stamped" keys from "tenant-supplied" keys. A webhook that tries to set `_profile_id` directly in its `claims` map (instead of via the top-level `profileId` field) is REJECTED — same posture as channel-reserved keys. This matters because:

- The materializer enforces the rule that reserved keys can't be supplied via tenant-controlled paths.
- A tenant that wants per-user profile routing uses the top-level field; that's the documented contract.
- A tenant that doesn't want per-user routing just doesn't set the field.

### 5.4 Validation

When `_profile_id` is present, the worker validates:

- The profile id exists for this tenant. If not, the inbound is treated as `no_binding_matched` — the webhook misnamed a profile.
- The profile is ACTIVE (not deactivated). If deactivated, same treatment.
- Cross-tenant profile ids are rejected: the worker only looks within the inbound's tenant.

Validation runs on every inbound — there's no caching of "is this profile valid?" because the resolver registry cache already short-circuits the webhook call for repeat phones.

### 5.5 What this unlocks for tenants

A retail bank example:

| User | Phone | What HTTP webhook returns | Effective profile |
|---|---|---|---|
| Alice (premium account) | +1555000001 | `{subject: u_42, profileId: retail-vip}` | retail-vip |
| Bob (standard account) | +1555000002 | `{subject: u_43, profileId: retail-customer}` | retail-customer |
| Carol (corporate account) | +1555000003 | `{subject: u_44, profileId: corporate-treasury}` | corporate-treasury |
| Dan (not in DB)           | +1555000004 | `{ found: false }`     | (denyOnUnknown=false → Anonymous → falls back to binding's default) |

All four flow through the SAME binding on the SAME sender. No keyword routing, no claim-based routing rules — the lookup answers everything.

The previous "use claim routing on `EndUserClaim`" model still works for cases where the webhook doesn't return profileId (e.g. the tenant prefers static routing). This is purely additive.

---

## 6. Configuration shape

```yaml
yaya:
  agentic:
    channel:
      whatsapp:
        auth:
          http-resolvers:
            - id: acme-customer-lookup
              description: "Resolves retail-customer phones via Acme's lookup webhook"
              url: https://acme.example/yaya/phone-lookup
              auth-kind: BEARER             # NONE | BEARER | SERVICE_TOKEN_HMAC
              bearer-token-env: ACME_LOOKUP_TOKEN
              timeout: PT3S
              # Optional: which tenants this resolver is permitted for. Empty = all.
              # Operators usually want the inverse — restrict so a bug in a webhook
              # doesn't get triggered by some unrelated tenant's inbound.
              allowed-tenants: [acme]

            - id: beta-partner-lookup
              description: "Beta's B2B partner phone resolver"
              url: https://api.beta.example/internal/yaya/lookup
              auth-kind: SERVICE_TOKEN_HMAC
              service-token-secret-env: BETA_LOOKUP_HMAC_SECRET
              timeout: PT2S
              allowed-tenants: [beta]
```

`bearer-token-env` and `service-token-secret-env` reference environment variable NAMES, not the secrets themselves. Storing the variable name in yaml is operator-safe; the secrets land in the platform's secret manager and surface as env vars. The `*-env` suffix convention matches the existing `JwtSecretCipher` model.

### 6.1 Authentication modes

| `auth-kind` | What yaya-agentic sends | When to use |
|---|---|---|
| `NONE` | No `Authorization` header | Internal-network deployments only (e.g. service mesh with mTLS at the edge). **Refuses to start** unless the URL is private/RFC-1918. |
| `BEARER` | `Authorization: Bearer ${env}` | Most tenants. Tenant validates the bearer at their edge and trusts the call. |
| `SERVICE_TOKEN_HMAC` | `X-Yaya-Service-Token: tenant.subject.exp.hmac` (same shape as the existing `SERVICE_TOKEN` AuthForwarding) | Tenants who want unforgeable, time-bound, tenant-scoped tokens with no shared bearer that could be replayed cross-tenant. |

The `SERVICE_TOKEN_HMAC` shape MIRRORS the existing `auth/service_token/` package's token format so tenants who already verify yaya-agentic service tokens for tool dispatch can reuse their existing verifier. One library, one trust model.

### 6.2 No per-tenant runtime configuration in v1

Resolver configs are yaml-only. v2 may add a CRUD admin endpoint for tenant ops to manage their own webhook URLs without operator intervention, but v1 keeps the trust surface narrow: only the platform operator can add a new HTTP resolver.

---

## 7. Failure modes & operator behavior

| Failure | Coordinator behavior | What lands in `step_up_audit` / logs |
|---|---|---|
| HTTP 200 + `found: false` | Treated as unknown user → fall back per `denyOnUnknown` | nothing — this is a normal outcome |
| HTTP 404 + any body | Same as above | nothing — normal outcome |
| HTTP 200 + valid `found: true` | Principal minted from response, profile picked from `profileId` (if present) | nothing — normal success |
| HTTP 200 + `found: true` but no `subject` | Resolver failure → fall back per `denyOnUnknown` | warn-level log: `http_resolver_invalid_response` |
| HTTP 200 + `claims` contains a reserved key | Response rejected → fall back per `denyOnUnknown` | warn-level log: `http_resolver_reserved_claim_leak` |
| HTTP 200 + `profileId` doesn't exist for tenant | Inbound treated as `no_binding_matched` | warn-level log: `http_resolver_unknown_profile` |
| HTTP 4xx | Resolver failure → fall back per `denyOnUnknown` | warn-level log: `http_resolver_4xx` |
| HTTP 5xx | Resolver failure → fall back per `denyOnUnknown` | warn-level log: `http_resolver_5xx` |
| Timeout | Resolver failure → fall back per `denyOnUnknown` | warn-level log: `http_resolver_timeout` |
| TLS failure / connection refused | Resolver failure → fall back per `denyOnUnknown` | warn-level log: `http_resolver_connect_failed` |
| Webhook responds in > 5s on the WHATSAPP webhook hot path | Inbound dispatch ACK to Twilio is unaffected — lookup runs in the dispatch worker AFTER the 200 OK to Twilio | n/a |

The last row is critical: the lookup happens on the dispatch worker, not the webhook thread. A slow lookup (or a tenant whose backend is having a bad day) NEVER causes Twilio to time out and retry the inbound.

---

## 8. Caching

The existing `PhoneToPrincipalResolverRegistry` cache (5-min TTL by `(tenant, sender, phone)`) applies unchanged. The HTTP call happens at most once per phone per 5-min window.

For high-traffic tenants this matters: a popular customer's first message in a 5-min burst triggers ONE lookup; the next 50 messages from that customer in the next 5 minutes are served from the in-memory cache.

The cache is cleared on `registry.invalidateAll()` — which is exposed but not called automatically. v2 may add a "wipe this phone" admin endpoint for incident response (e.g. when a tenant rotates a user's subject id).

`profileId` is part of the cached `Principal.claims`, so per-user profile routing is also memoized.

---

## 9. Security boundaries

### 9.1 The webhook IS the tenant's trust root

The tenant's webhook responds with "this phone is user u_42, give them these scopes." The platform trusts that response. If the webhook is compromised (XSS at the tenant's edge, stolen bearer, accidentally public URL) an attacker can impersonate any tenant user via WhatsApp.

This is the SAME posture as `DelegatedHostAuthenticator` (tenant-signed identity envelopes) and `OidcAuthenticator` (tenant-issued JWTs). Tenants who can secure those can secure this. The runbook will note that step-up should be configured on sensitive actions specifically so a compromised lookup endpoint doesn't auto-elevate.

### 9.2 What the webhook never sees

- **Inbound message text.** The lookup runs on identity metadata only.
- **Other tenants' phones.** `allowed-tenants` config restricts which tenants can trigger this webhook; misrouted inbound never calls a webhook that doesn't belong to the tenant.
- **Conversation history.** The lookup is stateless; the webhook can't read past sessions or peek at WhatsApp history.

### 9.3 What yaya-agentic guarantees back to the webhook

- **Request authenticity.** With `BEARER` or `SERVICE_TOKEN_HMAC`, the webhook can be sure the request came from yaya-agentic (BEARER) or from a specific tenant via yaya-agentic (SERVICE_TOKEN). With `NONE` the webhook is on its own — refuses to start unless the URL is private.
- **Tenant id is canonical.** `request.tenantId` is the platform-canonical id, not a user-controlled value. The webhook can use it as a foreign key into its multi-tenant store.

### 9.4 PII

The request body carries phone + sender + locale + Twilio contact SID. All of those are platform-internal metadata; none are conversation content. The response carries whatever the tenant wants — typically subject + display name + email. Standard PII tagging applies to the response claims: they flow into the recorder via the standard claim-pii-flag pipeline, and operators with `pii:read` can see them masked in audit.

The request body is logged at DEBUG, never INFO. The response body is logged at DEBUG with claims redacted to keys-only. Production deployments run at INFO; there's no path for PII to land in regular logs.

---

## 10. The HTTP-resolver class — design summary

One Spring `@Component` reading the yaml config list at boot:

```java
@Component
public class HttpPhoneToPrincipalResolver
        implements PhoneToPrincipalResolver,
                   PhoneToPrincipalResolverRegistry.DescribesPerExposedId {

    private final Map<String, HttpResolverConfig> byId;
    private final WebClient client;

    @Override public String id() { return "http"; }     // primary; unused
    @Override public Set<String> exposedIds() { return byId.keySet(); }
    @Override public String describeFor(String exposedId) { return byId.get(exposedId).description(); }

    @Override
    public Optional<Principal> resolve(TenantId tenant, SenderId sender,
                                       String phone, ResolutionContext ctx) {
        // 1. Find a config matching the resolverId being invoked
        // 2. Build the request body + headers (auth, trace, resolver-id)
        // 3. POST with the configured timeout
        // 4. On 200 + found:true → build Principal (subject, scopes, claims merged with profileId-as-reserved-claim)
        // 5. On 200 + found:false / 404 → return empty
        // 6. On any other outcome → log structured warning + return empty
    }
}
```

The "which config" question — answered by passing the `resolverId` through the `ResolutionContext`. The registry already routes the call to the right resolver based on id; we extend `ResolutionContext` with the matched `resolverId` so multi-id resolvers know which config to use.

---

## 11. SPI changes summary

| Type | Change |
|---|---|
| `PhoneToPrincipalResolver` | `+ default Set<String> exposedIds() { return Set.of(id()); }` — backward-compatible; existing impls unchanged. |
| `PhoneToPrincipalResolverRegistry` | Indexes by all exposed ids. Admin enumeration uses exposed ids. New helper interface `DescribesPerExposedId` for per-id descriptions. |
| `ResolutionContext` | `+ String resolverId` — populated by the registry when invoking; multi-id resolvers consult it. Defaults to the resolver's primary id for single-id resolvers. |
| `PrincipalPolicyMaterializer.RESERVED_CLAIM_KEYS` | `+ "_profile_id"`, `+ "_profile_version"`. Webhook responses' `claims` map can't supply these. |
| `InboundDispatchWorker` | After authentication, check `principal.claims._profile_id` and, if present + valid for the tenant, use it instead of `binding.profile()`. |

---

## 12. Configuration validation at boot

- Each `http-resolver` entry must have a non-blank `id`, `url`, and (when `auth-kind != NONE`) the appropriate `*-env`.
- `id` MUST be kebab-case (matching the existing convention).
- `id` MUST NOT collide with another resolver's id — same rule as today.
- `url` MUST parse as a valid URI. `auth-kind == NONE` requires `url.host` to be private (RFC-1918, `*.internal`, `localhost`, etc.) — public URLs without auth are refused.
- `timeout` defaults to `PT3S`. Caps at `PT10S` (no reason to wait longer than Twilio's webhook retry window).
- `allowed-tenants` is optional. Empty list = all tenants permitted.

Validation runs at startup; an invalid config aborts boot loudly. There's no degraded-mode fallback — a misconfigured webhook is operator misconfiguration.

---

## 13. Open questions

1. **Should we allow a tenant-side admin UI to register HTTP resolvers without operator intervention?** Probably — it's the natural v2 step. Today the operator is the trust gatekeeper; v2 lets workspace ADMINs add their own webhook within rate limits.

2. **Should `SERVICE_TOKEN_HMAC` include the phone number in the signed tuple?** Current shape is `tenant.subject.exp.hmac`. Adding phone gives the tenant a tamper-evident binding "this request really is about THIS phone" — useful for high-security tenants. Probably yes in v1; small change.

3. **Should we allow the webhook to return a SHORT-LIVED Principal (e.g. with a per-call `ttl` field)?** Today the registry's 5-min cache caches the whole resolve. If a tenant wants per-user TTL (a user's tier upgrade should propagate within seconds), they'd need to override. Defer — most tenants are fine with 5 min.

4. **Per-binding override of cache TTL.** Today the cache is platform-wide 5 min. A high-stakes profile might want 30s. Defer.

5. **Should the webhook get a chance to refuse on EVERY call (per-message authZ at the lookup layer)?** Out of scope — that's what the Authorizer chain is for. The lookup is identity only.

---

## 14. Test plan

| Layer | Test |
|---|---|
| Unit | `HttpPhoneToPrincipalResolver` happy path: webhook returns subject + scopes + claims + profileId → Principal carries reserved claims. |
| Unit | `_profile_id` and `_profile_version` from response are stamped as reserved claims; `claims` carrying those keys → rejected. |
| Unit | Auth modes: NONE rejected on public URL; BEARER stamps Authorization; SERVICE_TOKEN_HMAC stamps X-Yaya-Service-Token with correct shape. |
| Unit | Wire shape: request body has tenantId/senderId/e164Phone/channelKind; null channelLocale/twilioContactSid are omitted (not serialized as null). |
| Unit | `allowed-tenants` filter: a tenant not in the list never invokes the webhook. |
| Integration | Against MockWebServer: 200 + found:true with valid response → success; 200 + found:false → empty; HTTP 404 → empty; HTTP 5xx → empty + warn log; timeout → empty + warn log; malformed JSON → empty + warn log. |
| Integration | `_profile_id` from response → `InboundDispatchWorker` picks that profile, not the binding's default. |
| Integration | `_profile_id` referencing a non-existent profile → `no_binding_matched` audit row. |
| Integration | Registry: multi-id resolver shows N entries in `registry.all()`, each with the right description. |
| Integration | Cache: first call hits webhook, second within 5 min serves from cache; cache key is (tenant, sender, phone). |
| Security | Greptest: phone number never lands in INFO-level logs, only DEBUG (consistent with existing PII-not-in-logs rules). |
| Security | The webhook is invoked with the canonical `tenantId`, never a user-controllable value. |

---

## 15. Milestone breakdown

Single milestone — ~1.5 days of focused work:

- **W-A10.1** — SPI extension (`exposedIds`, `ResolutionContext.resolverId`, registry multi-id indexing). Tests for the registry changes. (~3 hours)
- **W-A10.2** — `HttpPhoneToPrincipalResolver` + `HttpResolverProperties` + `HttpResolverConfig`. NONE/BEARER auth modes. Tests against MockWebServer. (~4 hours)
- **W-A10.3** — `SERVICE_TOKEN_HMAC` auth mode reusing `auth/service_token/`'s shape. Tests. (~2 hours)
- **W-A10.4** — `PrincipalPolicyMaterializer` reserves `_profile_id` / `_profile_version`. Webhook-response-to-Principal mapping. (~2 hours)
- **W-A10.5** — `InboundDispatchWorker` consults `_profile_id` for per-user profile selection. Validation: profile exists + ACTIVE + same tenant. Integration test. (~3 hours)
- **W-A10.6** — Runbook addition: configuring an HTTP resolver, the wire contract, the auth modes, per-user profile routing. (~1 hour)

The total is ~15 hours of focused work, fits in 2 days with breaks.

---

## 16. The migration story (none required)

The change is purely additive:

- Existing Java-bean resolvers (Static, tenant-shipped beans) unchanged.
- Existing bindings using `LookupPrincipal(resolverId=...)` against a Java bean unchanged.
- Existing webhook-less deployments unchanged (no `http-resolvers` in yaml = no HTTP resolver beans).
- Tenants migrate from `PhoneAsPrincipal` to `LookupPrincipal(resolverId=their-http-id)` at their own pace by editing the binding.

---

## 17. Example: a tenant migration

A retail bank running yaya-agentic-as-a-service today has bindings configured as `PhoneAsPrincipal`. They want per-user identity + per-user profile routing.

**Step 1: build the webhook.** Their backend already has a `users` table. They add an endpoint:

```python
# Their Flask / Express / Rails / Go service
@app.post("/yaya/phone-lookup")
@require_bearer("YAYA_LOOKUP_TOKEN")
def phone_lookup():
    body = request.json
    user = db.users.find_one(phone=body["e164Phone"])
    if not user:
        return {"found": False}, 200
    return {
        "found": True,
        "subject": str(user.id),
        "scopes": user.scopes,           # ["order:read", "transfer:initiate"]
        "claims": {
            "email": user.email,
            "displayName": user.name,
            "tier": user.tier,
        },
        "profileId": "retail-vip" if user.tier == "premium" else "retail-customer",
    }
```

**Step 2: configure yaya-agentic.** Their platform operator adds:

```yaml
yaya.agentic.channel.whatsapp.auth.http-resolvers:
  - id: acme-bank-lookup
    description: "Resolves Acme Bank customers"
    url: https://api.acmebank.example/yaya/phone-lookup
    auth-kind: BEARER
    bearer-token-env: ACME_BANK_LOOKUP_TOKEN
    timeout: PT3S
    allowed-tenants: [acme-bank]
```

**Step 3: flip the binding.** In the admin console, they edit their binding's Identity Policy → `Lookup principal` → `acme-bank-lookup`. They also clear the binding's static `profileId` if they want the webhook to be the sole source of truth.

**Step 4: ship.** Every inbound now triggers the webhook (within the 5-min cache). The bot's prompt context carries `displayName` and `email`. Premium users land on `retail-vip`; everyone else on `retail-customer`. No code change in yaya-agentic.

That's the product surface this design unlocks.
