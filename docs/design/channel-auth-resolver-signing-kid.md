# Channel Auth Resolver — Outbound `SIGNED_JWT` `kid` (and `aud`) Addendum

> **Amends** `channel-auth-resolver-design.md` §6.3 ("How yaya authenticates
> ITSELF to the tenant"). Everything else in that doc stands. This addendum
> adds a per-resolver **signing key id** to the JOSE header of the outbound
> `SIGNED_JWT`, plus an optional **audience override**, so a tenant whose
> verifier keys its trust on `kid`/`aud` can accept yaya's self-auth.

| | |
|---|---|
| **Status** | 🟢 Implemented — migration `V47`, single PR per §8 |
| **Owner** | Yenfri Herrera |
| **Last updated** | 2026-06-29 |
| **Scope** | Two new optional columns on `channel_auth_resolvers` — `service_jwt_kid` and `service_jwt_audience` — threaded through the entity, factory, `HttpAuthResolver` mint path, admin CRUD, and the Flutter form. No new SPI, no sealed-type variant. |
| **Out of scope** | Multi-key rotation / JWKS publication by yaya (a single `kid` per resolver row). Asymmetric (RS/ES) outbound signing — `SIGNED_JWT` stays HS256 as today. |

---

## 1. The bug, precisely

`HttpAuthResolver.mintSignedJwt(...)` (`channel/auth/HttpAuthResolver.java:283`)
builds the outbound self-auth JWT with a bare header:

```java
SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
```

`new JWSHeader(alg)` emits `{"alg":"HS256"}` — **no `kid`**. A tenant verifier
that selects the verification key by `kid` (the smart-lottery integration, and
any standard multi-key JWKS-style verifier) cannot find a key → `missing_kid`
→ `401`. yaya reads that 401 as `self_auth_failed` (§14) and the whole
resolution fails closed.

The downstream verifier's *next* checks, once `kid` is present, are already
satisfied **except `aud`** (verified against the diagnostic from the consuming
app):

| Verifier check | yaya today | Status |
|---|---|---|
| `kid` present in header | absent | ❌ **this fix** |
| `aud` == `yaya` | `aud = req.tenant().value()` (the tenant id) | ⚠️ only passes if the tenant id literally equals the expected audience |
| signed with the key bound to that `kid` | `service_secret` (HS256) | ✅ once the secret matches the `kid` |
| `exp − iat ≤ 60s` | 30s lifetime | ✅ |
| `jti` == `X-Yaya-Request-Id` | `jwtID(requestId)` | ✅ |

So we fix `kid` (the cause) **and** make `aud` configurable in the same change,
to avoid a guaranteed second round-trip when the tenant's expected audience is
not their tenant id.

---

## 2. Decision: per-resolver column, not config/env

The `kid` could come from a deployment-wide config value or from a per-resolver
DB column. We choose the **column**, for one decisive reason:

> yaya-agentic is multi-tenant SaaS. Each tenant registers their own
> `channel_auth_resolvers` row, rotates their own `service_secret` from the
> admin console, and runs their own verifier expecting their own `kid`. A
> single env value (`IDENTITY_YAYA_INBOUND_KID`) cannot serve two tenants whose
> verifiers expect different key ids — it would force every tenant onto one
> shared id, which collides the moment a second tenant integrates.

The `kid` is **operator-owned configuration that travels with the secret**, not
deployment infrastructure. It belongs exactly where `service_auth_kind` and the
`service_secret` ciphertext already live: on the resolver row, self-served via
the existing admin CRUD. This mirrors `tenant_jwt_secrets`, where the inbound
HS-family trust is *already* keyed by `kid` per tenant — the outbound side
simply gains symmetry.

Same reasoning for `aud`: the expected audience is a fact about *the tenant's
verifier*, not about yaya's deployment, so it lives on the row.

---

## 3. Schema — migration `V47`

```sql
-- V47__channel_auth_resolver_signing_kid.sql
-- Per-resolver JOSE 'kid' for the outbound SIGNED_JWT header, plus an
-- optional audience override. Both nullable + backward compatible: a row
-- with NULL kid mints exactly today's header (no keyID), and a row with
-- NULL audience keeps aud = tenant_id. See
-- docs/design/channel-auth-resolver-signing-kid.md.

ALTER TABLE channel_auth_resolvers
    ADD COLUMN service_jwt_kid      VARCHAR(128),
    ADD COLUMN service_jwt_audience VARCHAR(255);

-- kid/audience are only meaningful for HTTP + SIGNED_JWT. We do NOT add a
-- CHECK forcing that shape: leaving a stale value on a row later switched
-- to NONE/BEARER is harmless (the mint path is the only reader, and it only
-- runs for SIGNED_JWT). Keeping the constraint surface narrow matches the
-- existing table, which also lets service_auth_kind/secret coexist loosely.
```

`service_jwt_kid` is **not a secret** — it's a public key identifier that ships
in the JWT header in cleartext by design. Unlike `service_secret`, it is *not*
encrypted and *is* safe to return on the GET surface (see §6).

---

## 4. The mint-path fix

`HttpAuthResolver` gains two final fields (`signingKid`, `audienceOverride`),
set from the constructor, and `mintSignedJwt` uses them:

```java
private String mintSignedJwt(ResolveRequest req, String requestId) {
    if (serviceSecret == null) {
        throw new AuthResolverException("self_auth_failed",
                "resolver '" + name + "' has SIGNED_JWT auth but no secret configured");
    }
    Instant now = Instant.now(clock);
    String audience = (audienceOverride == null || audienceOverride.isBlank())
            ? req.tenant().value()        // unchanged default — backward compatible
            : audienceOverride;
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(YAYA_ISSUER)
            .audience(audience)
            .subject("auth-resolver:" + name)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plus(SIGNED_JWT_LIFETIME)))
            .jwtID(requestId)
            .build();

    JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.HS256);
    if (signingKid != null && !signingKid.isBlank()) {
        header.keyID(signingKid);         // omitted when null → today's header
    }
    SignedJWT jwt = new SignedJWT(header.build(), claims);
    try {
        jwt.sign(new MACSigner(serviceSecret.getBytes()));
    } catch (JOSEException e) {
        throw new AuthResolverException("self_auth_failed",
                "resolver '" + name + "' failed to sign outbound JWT: "
                        + e.getClass().getSimpleName(), e);
    }
    return jwt.serialize();
}
```

`HttpAuthResolverFactory.build(...)` passes the two new row fields into the
constructor (alongside `serviceSecret`). Because `AuthResolverTestProbe` already
materializes resolvers through the same registry → factory path
(`registry.materialize(row)`), the **test probe signs identically to
production** — an operator clicking "Test" validates the real `kid`/`aud`, not
a stand-in.

**Backward compatibility is the load-bearing property here:** an existing row
with `service_jwt_kid = NULL` produces the exact byte-for-byte header it does
today. No existing working integration regresses. The `kid` is purely additive.

---

## 5. Admin API surface

Thread two nullable strings through the existing `TenantAuthResolverController`
DTOs and helpers — no new endpoints:

- `ResolverRequest` gains `String serviceJwtKid` and `String serviceJwtAudience`.
- `ResolverResponse` gains the same two (returned in cleartext — **not** secrets).
- `applyEditableFields(...)`: in the HTTP branch, set both from the request
  (trim-or-null); clear them on the BEAN branch alongside `serviceAuthKind`.
- `validateHttpFields(...)`: bound-check `serviceJwtKid` ≤ 128 chars and
  `serviceJwtAudience` ≤ 255 chars when present. **Not required** even for
  `SIGNED_JWT` — some verifiers (single shared HS secret, no `kid` selection)
  work without it, and forcing it would break those. Optional-but-recommended.

The `kid` being returnable is a deliberate divergence from `serviceSecret`: the
operator needs to *see* the configured `kid` to confirm it matches their
verifier's expectation (`yaya-in-1` in the reported case). It carries no secret
material — losing it to a GET response leaks nothing.

---

## 6. Flutter form

`yayaagenticweb/lib/features/admin/auth_resolvers/` and
`lib/models/admin/auth_resolver.dart`: add two text fields to the HTTP section
of the create/edit dialog, shown only when `serviceAuthKind == SIGNED_JWT`:

- **Signing key id (`kid`)** — helper text: *"The JOSE `kid` your verifier uses
  to select the HMAC key. Must match the key id bound to the shared secret on
  your side (e.g. `yaya-in-1`)."*
- **Audience override (`aud`)** — helper text: *"Defaults to your tenant id.
  Set this only if your verifier expects a different audience (e.g. `yaya`)."*

Both render read-only (cleartext) in the list/detail view, unlike the masked
secret. The test-probe result panel already shows the verifier's view of the
JWT; no change needed there beyond the values now flowing.

---

## 7. Tests

1. **Unit — `kid` in header.** Mint with `signingKid="yaya-in-1"`, parse the
   serialized JWT, assert `header.getKeyID().equals("yaya-in-1")`.
2. **Unit — backward compat.** Mint with `signingKid=null`, assert
   `header.getKeyID() == null` (byte-identical to today).
3. **Unit — `aud` override.** Mint with `audienceOverride="yaya"`, assert
   `claims.getAudience().equals(List.of("yaya"))`; with null, assert it equals
   the tenant id.
4. **Controller round-trip.** POST a SIGNED_JWT resolver with `serviceJwtKid`
   set; GET it back; assert `serviceJwtKid` is present in the response and
   `serviceSecret` is still absent / masked.
5. **Security sweep extension** (`ChannelAuthResolverSecuritySweepTest`): assert
   `serviceJwtKid` appearing in a response DTO does **not** trip the
   no-secret-in-response assertion (it is explicitly allowlisted as non-secret),
   while `serviceSecret` remains forbidden.

---

## 8. Cutover

A single PR — the change is small and the column is additive:

1. `V47` migration (§3).
2. `ChannelAuthResolverEntity`: two fields + getters/setters.
3. `HttpAuthResolver`: two constructor params + mint-path fix (§4).
4. `HttpAuthResolverFactory.build`: pass the two row fields.
5. `TenantAuthResolverController`: DTO + validation + `applyEditableFields` (§5).
6. Flutter form + model (§6).
7. Tests (§7).
8. One-line pointer from `channel-auth-resolver-design.md` §6.3 to this doc.

No data backfill: existing rows get `NULL` for both columns and keep their
current (working-or-not) behavior unchanged until an operator sets a `kid`.

---

## 9. Acceptance criteria

- A SIGNED_JWT resolver row with `service_jwt_kid="yaya-in-1"` mints an outbound
  JWT whose header is `{"alg":"HS256","kid":"yaya-in-1"}`; the smart-lottery
  verifier accepts it (no `missing_kid`).
- Setting `service_jwt_audience="yaya"` makes the `aud` claim `"yaya"`
  regardless of tenant id; leaving it null preserves `aud = tenant_id`.
- An existing resolver with no `kid` set behaves exactly as before this change.
- The admin GET surface shows `serviceJwtKid` / `serviceJwtAudience` in
  cleartext and never the secret.
- The admin "Test" button exercises the same `kid`/`aud` the live path uses.
