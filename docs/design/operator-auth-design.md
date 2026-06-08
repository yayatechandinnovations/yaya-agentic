# Operator Authentication — Design (v1, minimal)

> Companion to `yaya-agentic-design.md`. Touches §5.4 (Authentication SPI),
> §9 (API surface), §12 (safety), and supersedes the placeholder in
> `docs/milestones/M1-core-abstractions.md` F1.1.

| | |
|---|---|
| **Status** | 🟡 Proposed |
| **Scope** | Protect the admin REST surface (`/v1/admin/**`) and the Flutter console (admin + playground) behind authenticated operator sessions. Ship one built-in pluggable strategy — **HTTP delegate** — so host applications can validate operator logins against their own systems with zero yaya-agentic code changes. |
| **Out of scope (v1)** | OIDC / SAML flows; refresh-token rotation; signed-handoff (iframe trampoline); MFA; multi-tenant operator memberships; roles + scopes matrix; API keys for scripts. All are layered on top of this design later without breaking it. |

---

## 1. The one-paragraph summary

yaya-agentic ships with a **bootstrap operator** (username + password). On first boot the operator logs in, then chooses an authentication strategy from the admin console. The default strategy is **HTTP delegate**: the Flutter login form collects `{username, password}` and posts them to yaya-agentic; yaya-agentic POSTs them to a host-configured URL with a source-identifying header + shared secret; the host endpoint returns *allow* or *deny*. Bootstrap stays available as a break-glass fallback. Sessions are tracked server-side, identified by a HttpOnly cookie. That is the entire v1.

---

## 2. The two-plane principle (kept from the longer draft, in two sentences)

The existing `Authenticator` SPI in `auth/` is for **end-user / conversational** callers (the `Principal` talking to a profile-bound bot). This design adds a **separate** `OperatorAuthenticator` SPI for the humans configuring the platform; the two SPIs never share types, chains, or sessions. That separation prevents an operator cookie from ever being mistaken for a runtime `Principal` and vice versa — a single shared identity type would be the entire class of token-confusion bugs.

---

## 3. The SPI

```java
// operator_auth/OperatorAuthenticator.java
public interface OperatorAuthenticator {

    /** Stable identifier, recorded in audit + used by the admin UI dropdown. */
    String name();

    /**
     *  Optional.empty() → "this strategy isn't applicable" (let the chain continue)
     *  throw            → "applicable AND denied" (chain stops, return 401)
     *  present          → verified operator
     */
    Optional<Operator> tryAuthenticate(OperatorCredentials creds)
            throws OperatorAuthenticationException;
}

public record OperatorCredentials(
        String username,
        char[] password,            // cleared after the chain runs
        String clientIp,
        String userAgent,
        String attemptId            // ULID for cross-system audit correlation
) {}

public record Operator(
        String subject,             // stable identifier — "alice@acme.com" or whatever the strategy returns
        String displayName,
        Source source,              // which strategy authenticated this operator
        Map<String, Object> attributes,
        Instant verifiedAt
) {
    public enum Source { BOOTSTRAP, HTTP_DELEGATE }
}
```

`OperatorAuthenticatorChain` runs candidates in `@Order` and returns the first hit. Default order: `HttpDelegateOperatorAuthenticator` (200) → `BootstrapOperatorAuthenticator` (1000, last). The bootstrap is always last so a working delegate takes precedence, but it's always present so a broken delegate doesn't lock everyone out.

---

## 4. Bootstrap operator

A single username + password baked into config. Source-of-truth resolution, in order:

1. `yaya.agentic.operator-auth.bootstrap.username` + `password-hash` (argon2id), set via env var or `application.yml`. Recommended for prod.
2. `yaya.agentic.operator-auth.bootstrap.username` + `password` (plaintext), hashed at boot. Convenient for local + Docker compose.
3. **Neither set** → on first boot, yaya-agentic generates a random 24-char password, prints **once** with a `[SECURITY]` log tag, and writes the argon2id hash to a state file (`var/yaya/bootstrap.hash`). Reuses on subsequent boots.

The bootstrap operator behaves identically to a delegate-authenticated operator after login — same session, same audit, same admin scope. It is **not** stored in the database; it lives entirely in config + memory. This means rotating it is a config change + restart, not a SQL migration.

**Break-glass guarantee.** The chain *always* includes `BootstrapOperatorAuthenticator`. An operator can flip `yaya.agentic.operator-auth.bootstrap.enabled=false` after they've validated the delegate, but the doc + the admin UI warn loudly that this trades break-glass for one less attack surface — most deployments should leave it `true`.

---

## 5. `HttpDelegateOperatorAuthenticator` — the workhorse

The host already has a login endpoint. yaya-agentic should be able to point at it **as-is** — same URL, same request shape it already accepts, same response shape it already produces — and be configured to interpret it. Customers do not write a new endpoint to satisfy our contract; we adapt to theirs.

The strategy is built around three configurable layers:

| Layer | What the operator configures |
|---|---|
| **Request shaping** | method, headers, body format (`json` / `form` / `basic-auth` / `none`), body template with `{{username}}` / `{{password}}` substitution |
| **Success criteria** | a small bounded set of predicates over the response — status code, JSONPath existence, JSONPath equality. ALL configured criteria must match for *allow* |
| **Identity mapping** | JSONPaths into the response body for `subject`, `displayName`, `attributes`, plus a `reasonPath` for the audit row on denial |

The shaping vocabulary reuses the same Mustache substitution and `com.jayway.jsonpath` library that `HttpToolSpec` (M1.B1.8) already uses for tool calls — no new dependencies, and operators who have already configured an HTTP tool see familiar fields.

### 5.1 Request shaping

```yaml
request:
  method: POST                                          # default POST
  headers:
    Authorization: "Basic {{basic}}"                    # optional, operator-supplied
    X-Some-Tenant-Header: "{{username | domain}}"       # any extra headers the endpoint requires
  body:
    format: json                                        # json | form | basic-auth | none
    template: |
      {"email":"{{username}}","password":"{{password}}"}
```

- `format: json` → `Content-Type: application/json`; `template` is the literal body with `{{username}}`/`{{password}}` substituted. Values are **JSON-string-escaped on substitution** so a password containing `"` or `\` can't break the envelope.
- `format: form` → `Content-Type: application/x-www-form-urlencoded`; values are percent-encoded. Default template: `username={{username}}&password={{password}}`.
- `format: basic-auth` → no body; `Authorization: Basic {{basic}}` header is auto-added where `{{basic}}` is Base64(`username:password`).
- `format: none` → no body; useful when everything ships in headers or as a `GET` with credentials in `Authorization`.

**Two headers are always added by yaya-agentic and are not operator-overridable:**

```
X-Yaya-Source:        yaya-agentic
X-Yaya-Source-Secret: <configured>
X-Yaya-Attempt-Id:    <ULID>
```

- `X-Yaya-Source` is the **declared intent** — tells the host endpoint which caller is asking so it can branch policy ("operators can log into yaya-agentic only if they're in the `agent-admins` group").
- `X-Yaya-Source-Secret` is the **proof** — without it, anyone on the network could send the same source header to probe credentials. The host endpoint MUST verify it.
- `X-Yaya-Attempt-Id` is a ULID echoed into both sides' audit rows for cross-system correlation.

An operator who tries to set those keys in `headers:` gets a config-save warning and the override is silently dropped — they're how yaya-agentic identifies itself, not how it talks to the endpoint.

### 5.2 Success criteria

```yaml
success:
  statusIn: [200]                              # default [200, 204]
  jsonPathExists: "$.data.user.id"             # optional
  jsonPathEquals:                              # optional, list — every entry must match
    - { path: "$.status", value: "ok" }
    - { path: "$.error",  value: null }        # explicit-null match
```

**AND semantics:** every configured criterion must match. If a customer needs OR, they can configure two delegates (future) or pick a single criterion that's permissive enough.

The criteria set is intentionally small and bounded — no general expression language, no JsonLogic, no Rego. If a customer needs something we can't express, they wrap their endpoint. (We have never regretted choosing a small DSL over a big one.)

### 5.3 Identity mapping

```yaml
identity:
  subjectPath:     "$.data.user.email"         # optional; default = the typed username
  displayNamePath: "$.data.user.name"          # optional; default = subject
  attributesPath:  "$.data.user"               # optional; subtree copied verbatim into Operator.attributes
```

If `subjectPath` is **configured** but the path doesn't resolve to a non-null scalar on an otherwise-successful response, the login is **denied** with `audit_reason = identity_extraction_failed`. This prevents an endpoint that returns 200 with an empty body from minting a phantom operator.

If `subjectPath` is **not** configured, we trust the typed username as the operator's subject. The admin UI surfaces this at config-save time with a soft warning, because in that mode yaya-agentic is the source of truth for *who* logged in — fine when the endpoint already validated identity (you'd only see a 200 if the typed username matched a real user), worth flagging.

### 5.4 Failure-reason extraction

```yaml
failure:
  reasonPath: "$.error.code"                   # optional
```

When the response body is present but the success criteria don't match, `reasonPath` is evaluated and the result lands in the `audit_reason` column. **Never** shown to the user — the UI always displays the generic *"Invalid username or password"*.

### 5.5 Defaults that "just work"

An operator who only fills in `url` and `shared-secret` and nothing else gets:

- `method: POST`, `format: json`, body template `{"username":"{{username}}","password":"{{password}}"}`
- `success: statusIn: [200, 204]`
- `identity.subjectPath` unset → subject = typed username

That's enough for any endpoint that already accepts JSON `{username, password}` and returns 200 on success. Probably ~60% of integrations end here.

### 5.6 Worked examples — adapting to existing endpoints unchanged

**A. Endpoint expects JSON `{email, password}`, returns 200 + user object:**

```yaml
url: "https://api.example.com/v1/sessions"
request:
  body:
    template: '{"email":"{{username}}","password":"{{password}}"}'
success:
  statusIn: [200]
identity:
  subjectPath:     "$.user.email"
  displayNamePath: "$.user.full_name"
```

**B. Legacy endpoint always returns 200, signals success in the body:**

```yaml
url: "https://legacy.example.com/auth/check"
success:
  jsonPathEquals:
    - { path: "$.status", value: "ok" }
identity:
  subjectPath: "$.account.id"
failure:
  reasonPath: "$.message"
```

**C. HTTP Basic Auth, no body:**

```yaml
url: "https://intranet.example.com/whoami"
request:
  method: GET
  body:
    format: basic-auth
success:
  statusIn: [200]
identity:
  subjectPath: "$.sAMAccountName"
```

**D. Form-encoded login, success signalled via 302:**

```yaml
url: "https://app.example.com/login"
request:
  body:
    format: form                                # default template username=…&password=…
success:
  statusIn: [200, 302]
```

In none of these does the customer touch their existing endpoint. yaya-agentic adapts.

### 5.7 Full configuration shape

```yaml
yaya:
  agentic:
    operator-auth:
      bootstrap:
        enabled: true
        username: admin
        password-hash: "$argon2id$..."
      http-delegate:
        enabled:        false
        url:            "https://host.example.com/internal/yaya-login"
        shared-secret:  "${YAYA_DELEGATE_SECRET}"
        timeout:        5s
        require-https:  true
        request:
          method:  POST
          headers: { }
          body:
            format:   json                       # json | form | basic-auth | none
            template: '{"username":"{{username}}","password":"{{password}}"}'
        success:
          statusIn:        [200, 204]
          jsonPathExists:  ~                     # null = not checked
          jsonPathEquals:  [ ]                   # empty = not checked
        identity:
          subjectPath:     ~                     # null = use typed username
          displayNamePath: ~                     # null = use subject
          attributesPath:  ~
        failure:
          reasonPath:      ~
```

Same config is editable from the admin UI (§8) and persisted to a single-row `operator_auth_config` table. The admin UI is the operator-facing source of truth; the YAML bootstraps the row on first boot.

### 5.8 Network behavior on the response

- **2xx response, success criteria match** → allow.
- **2xx response, success criteria don't match** → deny with `audit_reason` from `failure.reasonPath` if set, else `success_criteria_unmet`.
- **3xx response** → followed only if `statusIn` allows the *final* status (default behavior: yes, `WebClient` follows up to 3 redirects). Operators who want 302-as-success disable redirect-follow per-config.
- **4xx response** → deny with `audit_reason = delegate_status_<code>`.
- **5xx, network failure, timeout** → deny with `audit_reason = delegate_unreachable`; chain falls through to `BootstrapOperatorAuthenticator` so a working bootstrap can still rescue the operator.
- **Malformed body when a JSONPath is configured** → deny with `audit_reason = delegate_malformed_response`.

User-facing message is the same generic string in every denial path. We never leak which strategy denied, why it denied, or whether the endpoint was reachable.

---

## 6. Sessions — server-side, cookie-bound, no JWT

JWTs are overkill for one form login. We use a server-side session.

- On successful authentication, yaya-agentic inserts a row in `operator_sessions` with `(id, operator_subject, source, created_at, expires_at, last_seen_at, ip, ua)`. `id` is a 256-bit random opaque string, hashed (SHA-256) before storage so a leaked DB doesn't grant session takeover.
- The id is returned to the browser as `Set-Cookie: YAYA_SESSION=<id>; HttpOnly; Secure; SameSite=Lax; Path=/`. The browser can't read it from JS; XSS can't exfiltrate it.
- Expiry: 8h absolute, 1h sliding (any request inside the window extends `expires_at` by 1h up to the 8h ceiling). Sliding is recomputed in a `@Transactional` no-op so the write doesn't block the request.
- Logout: server deletes the row, sends `Set-Cookie: YAYA_SESSION=; Max-Age=0`.

**CSRF.** A `XSRF-TOKEN` cookie (NOT HttpOnly, so the SPA can read it) is set on login; the SPA echoes it as `X-XSRF-TOKEN` on every state-changing request. Mismatch → 403. SSE endpoints (`POST /v1/sessions/{id}/messages`) — but those are the *end-user* plane, not protected by this filter — are exempt by virtue of being on a different chain.

**Why not Spring Security's `formLogin()`?** Considered. Rejected because (a) the project doesn't currently depend on `spring-boot-starter-security` and pulling it in for one filter is a heavy classpath add, (b) Spring Security's session model assumes its own `UserDetailsService` and our chain semantics (`Optional`-or-throw) don't map cleanly. We will hand-roll one Servlet filter (~150 lines) and one session repository. Trivial to maintain; trivial to test.

---

## 7. Backend wiring

```
http filter ordering
─────────────────────
 1. OperatorAuthFilter        matches: /v1/admin/**, /v1/auth/me, /v1/auth/logout, /v1/playground/admin/**
                              reads YAYA_SESSION cookie, looks up session, sets OperatorContext threadlocal
                              missing/expired → 401

 2. CsrfFilter                matches: same paths, state-changing methods only
                              compares X-XSRF-TOKEN header to XSRF-TOKEN cookie

 3. (existing engine flow)    /v1/sessions/**   ← end-user plane, untouched
```

Login + bootstrap config endpoints (`/v1/auth/login`, `/v1/auth/logout`, `/v1/auth/me`, `/v1/auth/strategies`) are NOT protected by `OperatorAuthFilter` — login is what creates the session.

`OperatorContext` is a request-scoped bean that exposes `Operator current()` and is auto-populated by the filter. `AdminController` and `KnowledgeAdminController` accept it as a constructor-injected dependency; no `@RequireScope` annotation needed in v1 (every authenticated operator is implicitly admin — roles land in M5).

---

## 8. The "auth strategies" admin screen

A single screen in the Flutter console under **Settings → Operator authentication**, organised in collapsible sections so the common case stays one form-field tall.

**Top-level fields (always visible):**
- HTTP delegate on/off toggle.
- URL.
- Shared secret (write-only, displays as `••••••• [rotate]`).
- Timeout.

**Advanced — request shaping (collapsed):**
- Method picker (GET / POST / PUT).
- Body format radio (`json` / `form` / `basic-auth` / `none`); default `json`.
- Body template editor (Monaco; pre-filled with the format-appropriate default).
- Extra headers (key/value list). Reserved keys (`X-Yaya-Source`, `X-Yaya-Source-Secret`, `X-Yaya-Attempt-Id`) are disabled with a tooltip explaining they're added automatically.

**Advanced — success criteria (collapsed):**
- Status codes (comma list, default `200,204`).
- JSONPath exists (single path).
- JSONPath equals (list of `{path, value}` rows).
- Live validation: paths are compiled on blur; invalid paths block save.

**Advanced — identity mapping (collapsed):**
- Subject path, display-name path, attributes path.
- Failure reason path.
- Help text under `subjectPath` explains the "unset = trust typed username" semantics with a soft warning when unset.

**Test button (the key UX):** posts user-supplied test credentials to the delegate URL with the configured secret + request shape, evaluates the configured success/identity rules on the response, and shows the operator a structured summary:

```
┌──────────────────────────────────────────────┐
│  HTTP status:        200                     │
│  Response time:      127 ms                  │
│  Success criteria:   ✓ statusIn matched      │
│                      ✓ $.status == "ok"      │
│  Identity extracted: subject = alice@acme    │
│                      display = Alice Roe     │
│  Decision:           ALLOW                   │
│                                              │
│  [▾ Raw response body]                       │
└──────────────────────────────────────────────┘
```

Operators see exactly how their config interpreted the response, not just the raw bytes — which is what makes the difference between "I think this is wired right" and "I know it is" before they disable bootstrap.

**Bootstrap section:**
- Shows username; "Change password" form (current + new + confirm).
- Big yellow banner: *"Disabling bootstrap removes your break-glass account. Only do this after the delegate has logged in at least one operator successfully."*
- "Disable bootstrap" button is greyed until `audit_operator_login` shows at least one `ALLOW` from `source = HTTP_DELEGATE`.

That screen is gated by the same admin filter as everything else — so any logged-in operator can change auth config. That is fine in v1 (single-tenant, single operator class); when M5 adds roles, this screen will be `TENANT_ADMIN`-only.

---

## 9. Flutter — what changes in `yayaagenticweb/`

### 9.1 New package: `lib/features/auth/`

```
features/auth/
├── data/
│   └── auth_api.dart          POST /v1/auth/login, /logout; GET /v1/auth/me
├── domain/
│   ├── operator.dart          freezed Operator
│   └── auth_state.dart        sealed: Unauthenticated | Authenticating | Authenticated | Expired
├── application/
│   └── auth_controller.dart   Riverpod AsyncNotifier
└── presentation/
    └── login_screen.dart      username + password form, single submit button
```

### 9.2 Router changes (`app/router.dart`)

```dart
final appRouter = GoRouter(
  initialLocation: '/playground',
  refreshListenable: GoRouterRefreshStream(authStateChanges),
  redirect: (ctx, state) {
    final auth = ProviderScope.containerOf(ctx).read(authControllerProvider);
    final atLogin = state.matchedLocation == '/login';
    if (auth is! Authenticated && !atLogin) {
      return '/login?returnTo=${Uri.encodeComponent(state.matchedLocation)}';
    }
    if (auth is Authenticated && atLogin) return '/playground';
    return null;
  },
  routes: [
    GoRoute(path: '/login', builder: (_, __) => const LoginScreen()),
    ShellRoute(
      builder: (_, __, child) => _AppShell(child: child),
      routes: [ /* existing /playground, /admin/*, /settings */ ],
    ),
  ],
);
```

### 9.3 Dio interceptor (replaces the M0 placeholder)

```dart
dio.interceptors.add(InterceptorsWrapper(
  onRequest: (options, handler) async {
    // session cookie travels automatically; we just attach CSRF on writes
    if (_stateChanging(options.method)) {
      final xsrf = _readCookie('XSRF-TOKEN');
      if (xsrf != null) options.headers['X-XSRF-TOKEN'] = xsrf;
    }
    handler.next(options);
  },
  onError: (err, handler) {
    if (err.response?.statusCode == 401) {
      ref.read(authControllerProvider.notifier).markExpired();
    }
    handler.next(err);
  },
));
```

For browser deployments, Dio's `withCredentials: true` is required so the cookie is sent cross-origin (when the Flutter app is served from a different origin than the backend). Add it to the `BaseOptions` in `dioProvider`.

### 9.4 Login screen

One form, three fields: username, password, submit. On success, refresh `authControllerProvider` and navigate to `returnTo`. On 401, display *"Invalid username or password"* — the same string regardless of which strategy denied (don't leak strategy presence).

---

## 10. Audit

One table, one writer:

```sql
CREATE TABLE audit_operator_login (
    id              TEXT PRIMARY KEY,                 -- ULID
    at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    username        TEXT NOT NULL,                    -- never the password
    decision        TEXT NOT NULL CHECK (decision IN ('ALLOW','DENY')),
    source          TEXT,                             -- BOOTSTRAP | HTTP_DELEGATE | null when no strategy claimed
    audit_reason    TEXT,                             -- delegate_status_403 | delegate_unreachable | bootstrap_mismatch | …
    client_ip       TEXT,
    user_agent      TEXT,
    attempt_id      TEXT NOT NULL,                    -- echoes X-Yaya-Attempt-Id
    duration_ms     INTEGER
);
CREATE INDEX ON audit_operator_login(username, at DESC);
CREATE INDEX ON audit_operator_login(decision, at DESC);
```

Per the §5.5 convention in the master doc: the *user-facing* message is the same on every denial; the *audit* reason carries the truth. We do not log the password under any circumstance — the password char[] is zeroed after the chain runs.

A second table `audit_admin_ops` for admin requests is desirable but explicitly out of v1 — happy path first, observability when we have time. Until then, normal Spring access logs are enough.

---

## 11. Data model additions

```sql
CREATE TABLE operator_sessions (
    id_hash         BYTEA PRIMARY KEY,                -- SHA-256 of the cookie value
    operator_subject TEXT NOT NULL,
    operator_display TEXT NOT NULL,
    source          TEXT NOT NULL,                    -- BOOTSTRAP | HTTP_DELEGATE
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    client_ip       TEXT,
    user_agent      TEXT,
    attributes      JSONB                              -- verbatim from the delegate, opaque
);
CREATE INDEX ON operator_sessions(expires_at) WHERE expires_at > now();

CREATE TABLE operator_auth_config (
    id                          INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),  -- singleton
    bootstrap_enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    http_delegate_enabled       BOOLEAN NOT NULL DEFAULT FALSE,
    http_delegate_url           TEXT,
    http_delegate_secret_enc    BYTEA,                  -- KMS-wrapped or app-encrypted
    http_delegate_timeout_ms    INTEGER NOT NULL DEFAULT 5000,
    http_delegate_require_https BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                  TEXT
);

CREATE TABLE audit_operator_login ( /* per §10 */ );
```

A scheduled job (`@Scheduled(fixedDelay = 10m)`) deletes `operator_sessions` rows where `expires_at < now()`. Cheap, keeps the table small.

---

## 12. API surface additions

```
# Authentication
POST   /v1/auth/login              { username, password } → 200 + Set-Cookie OR 401
POST   /v1/auth/logout             204; clears cookie + session row
GET    /v1/auth/me                 current operator + source

# Auth strategy admin (requires login)
GET    /v1/admin/auth/strategies                   current config (secret masked)
PUT    /v1/admin/auth/strategies/bootstrap         { enabled, newPassword? }
PUT    /v1/admin/auth/strategies/http-delegate
    body:
      { enabled, url, secret?, timeout, requireHttps,
        request:  { method, headers, body: { format, template } },
        success:  { statusIn, jsonPathExists?, jsonPathEquals?[] },
        identity: { subjectPath?, displayNamePath?, attributesPath? },
        failure:  { reasonPath? } }
    400 on invalid JSONPath; 400 on http:// URL with require-https=true;
    400 on non-2xx values in success.statusIn without ?confirmPermissive=true.

POST   /v1/admin/auth/strategies/http-delegate/test
    body: { username, password }
    → 200 {
        request:  { method, url, headersSent, bodySent (with password redacted) },
        response: { status, durationMs, headers, body },
        evaluation: {
          successChecks: [ { criterion, matched, detail } ],
          identityExtracted: { subject, displayName, attributes } | null,
          decision: "ALLOW" | "DENY",
          auditReason: string | null
        }
      }

# Audit
GET    /v1/admin/audit/operator-logins?…           filterable list
```

---

## 13. Security considerations & footguns

| Concern | Mitigation |
|---|---|
| **Plaintext password forwarded over the network.** | Require HTTPS for the delegate URL in prod (override only via explicit `require-https=false` in `dev` profile). Document that the host endpoint must terminate TLS. |
| **Password lives in JVM memory between form parse and delegate POST.** | `OperatorCredentials.password` is `char[]`, not `String`, and zeroed by `OperatorAuthenticatorChain` before the request returns. (Strings would sit in the string pool.) |
| **Misconfigured delegate URL locks everyone out.** | Bootstrap is always-on by default. The "Disable bootstrap" toggle requires a confirmation modal that types the current bootstrap username verbatim. |
| **Anyone on the network can probe creds against the host endpoint.** | Shared secret is mandatory; host endpoint MUST verify it. yaya-agentic refuses to save a delegate config without a non-empty secret. |
| **Host endpoint reflects user-controlled input in `reason`.** | We never display the delegate's `reason` to the user — it goes only to audit. The user sees a fixed string. |
| **Operator configures a too-permissive success criterion** — e.g. `statusIn: [200, 401]` or an always-true JSONPath. | Config-save validation refuses non-2xx values in `statusIn` without an explicit confirmation flag. The test-delegate button surfaces the *parsed* result ("WOULD ALLOW because status 401 ∈ configured statusIn") so misconfiguration is obvious before going live. |
| **Endpoint returns 200 with empty body when configured `subjectPath` won't resolve.** | Deny with `audit_reason = identity_extraction_failed`. Test button shows the failed extraction. Prevents phantom-operator minting. |
| **JSON-injection via password containing `"` or `\`.** | Templating substitutes values through a format-aware escaper: JSON-escape for `format: json`, percent-encode for `format: form`, Base64 for `format: basic-auth`. Operators cannot disable escaping. |
| **Bootstrap default password is well-known.** | We don't ship a literal "admin/admin". Three options ranked: env-supplied hash > env-supplied plaintext (hashed at boot) > generated random with one-time log print. Document option 1 as the prod recommendation. |
| **Session cookie sent on the end-user plane by mistake.** | The end-user plane (`/v1/sessions/**`) explicitly ignores `YAYA_SESSION` — the engine reads `Principal` from the runtime `Authenticator` chain only. An operator cookie hitting the end-user plane has zero authority. |
| **Session fixation.** | On successful login, any prior `YAYA_SESSION` cookie is invalidated and a new id is issued. |
| **Credential stuffing.** | Per-username and per-IP rate limit on `/v1/auth/login` (5 attempts / 1 min / username, 30 attempts / 1 min / IP). Implemented with Bucket4j against Redis (already a project dep). |

---

## 14. Non-goals (v1) — what we explicitly defer

Everything below was in the original (over-engineered) draft. None of it is *wrong*, but none of it is necessary for v1 and shipping it now would block on decisions that don't have to be made yet.

- **OIDC code flow + JWKS + Yaya-issued JWTs + refresh-token rotation chains.** Re-introducible by adding `OidcOperatorAuthenticator` to the chain in a later milestone; the SPI is already shaped for it.
- **Signed Ed25519 handoff from a host app.** Reintroducible the same way. The HTTP delegate covers most "I have my own auth" cases already.
- **Multi-tenant operator memberships.** v1 assumes a single tenant (consistent with M1). The `Operator` record has no tenant field; when M5 adds it, the field is additive.
- **Roles + scopes matrix + `@RequireScope` aspect.** Every authenticated operator is admin in v1. Layering roles later is a backend-only change behind an annotation.
- **API keys for scripts / CI.** Defer until a customer asks. The same HTTP delegate covers "we have a script that knows the bootstrap password".
- **MFA / WebAuthn / TOTP.** Defer; in v1 the host's existing IdP carries the MFA story when HTTP delegate is wired to it.
- **SAML.** Long tail; covered by host-side SAML→whatever bridge.
- **Audit-of-audit, JIT elevation, per-tenant signing keys.** All M5+.

---

## 15. Open questions

1. **Where does the delegate secret live in the database?** Options: KMS envelope (AWS-only, adds dep); Spring `Encryptors.text` with a key from env (simple, works everywhere); plaintext column (rejected). Recommendation: `Encryptors.text` with `YAYA_CONFIG_KEY` env var; document key rotation.
2. **Should the delegate request include the tenant id?** Today there's only `default`. Adding `X-Yaya-Tenant` is forward-compatible and free; do it now so the host endpoint can branch when M5 multi-tenancy lands.
3. **Do we cache successful delegate responses?** Recommendation: no. Per-login cost is one HTTPS POST; the simplicity of "always live-check" is worth it. If a host endpoint becomes a bottleneck, we add a TTL cache later.
4. **What if the host wants yaya-agentic to send hashed passwords?** v1 says plaintext (over HTTPS). If a customer wants hashed, we add a `delegate-payload-format` field with values `plaintext` (default) and `bcrypt`/`argon2id` — but only when asked.

---

## 16. Sequencing

Land as **M1.5 — Operator Auth (minimal)**, between M1 and M2. Estimated ~1 week of work (vs ~3 weeks for the heavier draft). M1 deliberately left admin auth as TODO; closing it before M2 means the rest of the milestones never carry an open admin surface.

### Backend deliverables

- **B1.5.1** `operator_auth/` package + SPI + `Operator` + `OperatorCredentials` + chain.
- **B1.5.2** `BootstrapOperatorAuthenticator` + the three config-resolution modes (hash / plaintext / generated).
- **B1.5.3** `HttpDelegateOperatorAuthenticator` (uses the existing `WebClient` + the egress allowlist from M1.B1.12 — the delegate URL must pass the same SSRF check as any HTTP tool).
- **B1.5.4** `OperatorAuthFilter` + `OperatorContext` request-scoped bean.
- **B1.5.5** Hand-rolled CSRF filter (synchronizer-token cookie pattern).
- **B1.5.6** Session repository + scheduled cleanup.
- **B1.5.7** Login / logout / me controllers; auth-strategy admin controller; test-delegate endpoint.
- **B1.5.8** Flyway migrations for §11 tables; secret encryption wiring.
- **B1.5.9** Bucket4j rate-limit on login.
- **B1.5.10** `AdminExceptionHandler` updated to map `401` / `403` cleanly.

### Flutter deliverables

- **F1.5.1** Auth feature package (data / domain / application / presentation).
- **F1.5.2** Login screen; router redirect guard.
- **F1.5.3** Dio interceptor rewrite (CSRF echo; 401 → markExpired).
- **F1.5.4** Settings → Operator authentication screen with the test-delegate button.
- **F1.5.5** App-shell user menu (operator name + sign-out).

### Acceptance criteria

- [ ] Any unauthenticated request to `/v1/admin/**` returns 401 and writes an `audit_operator_login` row with `decision=DENY`.
- [ ] Unauthenticated navigation to `/playground` or `/admin/*` in the Flutter app redirects to `/login`.
- [ ] Bootstrap login with the configured username/password works; cookie is set; subsequent `/v1/admin/profiles` succeeds; logout clears the session row.
- [ ] HTTP delegate happy path with **default config only**: a Testcontainers endpoint that accepts `{username, password}` JSON and returns 200 is enough — operator sets URL + secret only, login works, subject is the typed username.
- [ ] HTTP delegate with custom **request body template**: operator configures `{"email":"{{username}}","password":"{{password}}"}`; endpoint receives the email-keyed payload verbatim; login succeeds.
- [ ] Success-by-body: endpoint returns 200 with `{"status":"ok"}` and `success.jsonPathEquals = [{path:"$.status",value:"ok"}]` → ALLOW. Same endpoint returns 200 with `{"status":"err"}` → DENY with `audit_reason=success_criteria_unmet`.
- [ ] Identity extraction: configured `subjectPath="$.user.email"` resolves → that becomes the operator subject. Configured path that doesn't resolve on a 200 response → DENY with `audit_reason=identity_extraction_failed`. Unset `subjectPath` → subject = typed username.
- [ ] Form-encoded mode: `format: form` sends `Content-Type: application/x-www-form-urlencoded`, percent-encodes a password containing `&` and `=` correctly.
- [ ] Basic-auth mode: `format: basic-auth` sends `Authorization: Basic <base64(user:pass)>` and no body; works against a `GET` endpoint.
- [ ] Reserved-header guard: operator sets `headers: { X-Yaya-Source: spoofed }` → save warning, override silently dropped, outbound request still carries the real values.
- [ ] HTTP delegate denial via `{allow:false, reason:"x"}` payload (using JSONPath success criteria): UI shows *"Invalid username or password"* (NOT the reason); audit row carries the extracted reason.
- [ ] HTTP delegate down: endpoint unreachable; chain falls through to bootstrap; bootstrap login still succeeds; audit row for the delegate attempt records `audit_reason=delegate_unreachable`.
- [ ] Bad shared secret: endpoint returns 403; user sees the same generic message; audit reason is `delegate_status_403`.
- [ ] Test-delegate endpoint returns the structured evaluation envelope (not just raw response) and the test password is redacted in the echoed `request.bodySent`.
- [ ] "Disable bootstrap" UI control is disabled until `audit_operator_login` shows at least one successful `HTTP_DELEGATE` login.
- [ ] Rate limit: 6th failed login attempt with the same username inside 1 min returns 429; audit records `RATE_LIMITED`.
- [ ] CSRF: a state-changing admin request without `X-XSRF-TOKEN` returns 403.
- [ ] Session fixation: a pre-existing `YAYA_SESSION` cookie is replaced (different id) after successful login.
- [ ] Plain HTTP delegate URL in prod profile is rejected at config-save time.

---

## 17. Summary of what changed vs the earlier draft

The earlier draft built out OIDC, signed handoffs, Yaya-issued JWTs, refresh-token rotation, JWKS publication, a roles-and-scopes matrix, API keys, per-tenant memberships, and Spring Security wiring — about three weeks of work. This v1 ships in roughly a week and intentionally **does only what is needed to protect the console**:

- One bootstrap user (always available as break-glass).
- One pluggable strategy (HTTP delegate) that **adapts to whatever endpoint the customer already has**: configurable request shape (JSON / form / Basic Auth / none), bounded success predicates (status code + JSONPath), JSONPath identity mapping. Customer writes zero code; they configure yaya-agentic to speak their endpoint's existing contract, not the other way around.
- Server-side sessions + cookies (no JWT infra).
- One audit table.
- No roles, no tenants, no JWKS, no rotation, no OIDC — all reintroducible later because the SPI shape doesn't preclude any of them.

When the platform actually needs multi-tenant operators, role matrices, or true OIDC SSO, we add them on top. Today, what's needed is "operators can log in against the auth endpoint they already have, the host app gets the final say, and unauthenticated visitors don't touch the admin UI."
