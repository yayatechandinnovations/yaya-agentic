# Milestone M4 — First Real Profile (Production-Ready)

| | |
|---|---|
| **Goal** | One end-to-end production-ready profile against a real domain. Ownership-based AuthZ, polished denial UX, at least one knowledge source, load tested, security reviewed. This is the proof that the framework is real, not a demo. |
| **Timeline** | Weeks 11–13 (design §17 — M4) |
| **Status** | 🟦 Planned |
| **Depends on** | M2.5, M3 |
| **Design refs** | §14 worked example · §15 risks · §12 safety |

## Scope (IN)

- Pick **one domain** for the v1 launch profile (TBD — see open questions). The worked example in §14 was financial-services-flavored ("retail-customer"); we will treat that as the placeholder until the domain is locked.
- Real domain tools (HTTP descriptors) against the target backend(s), exercising `AuthForwarding.PRINCIPAL_TOKEN` for user-bound actions and `AuthForwarding.SERVICE_TOKEN` for service-bound lookups.
- `OwnershipAuthorizer` configured against the real resource model (e.g. account ownership).
- At least one production knowledge source (e.g. a policy/SOP corpus) attached.
- Load test: sustained 50 RPS of conversation turns, p95 TTFT < 1.5s, p95 full-turn < 4s.
- Security review: prompt-injection corpus, SSRF probe, authz bypass attempts, RTBF path verified.
- Denial UX: the worked example from design §14 reproduces verbatim under load.
- Production deploy checklist signed off.

## Out of scope (NOT here)

- Additional profiles (each is its own M4-shaped slice later).
- S3 cold recorder + warehouse sinks (M5).
- Tenant isolation hardening (M5).

## Backend deliverables

### B4.1 — Real domain tool catalog
A set of `ToolDescriptor`s (Bean and/or HTTP) against the chosen domain. Each tool has:
- Reviewed JSON schemas with PII tagging.
- Declared `PermissionRequirement`.
- Explicit `AuthForwarding` choice with rationale documented inline.
- `ToolPolicy.confirmable=true` on every destructive tool.

### B4.2 — Real `Authorizer` chain for the domain
- Scope check.
- Ownership check against the real backend (e.g. `lookup_account_owner`).
- Optional policy step.
Each authorizer's `userSafeReason` strings reviewed by product/UX to match the brand voice.

### B4.3 — Production knowledge source
At least one ingested corpus (policies/SOPs/FAQ). Refresh cadence configured. Ingestion success/failure alerts wired.

### B4.4 — End-to-end conversation tests on real backend (staging)
Scripted scenarios pointing at staging APIs. Run as a nightly job, not on every PR.

### B4.5 — Load test
Gatling or k6 scenario: 50 RPS sustained, mixed conversation lengths, mixed RAG and non-RAG turns. Pass criteria above. Results checked into `docs/perf/M4-loadtest.md`.

### B4.6 — Security review
Tracked in `docs/security/M4-review.md`:
- Prompt-injection corpus (≥50 prompts) run against the profile; assert no policy violations.
- SSRF probe set against HTTP tool dispatcher.
- AuthZ bypass attempts (cross-account lookups, scope downgrades, token replay).
- RTBF path: end-to-end `DELETE /v1/admin/principals/{subject}` clears recorder + outbox + audit links.
- Findings filed; criticals fixed before exit.

### B4.7 — Kill-switch wiring
Per-profile and per-tool kill-switch backed by a feature-flag service (or simple DB row). Flipping the switch produces an in-character refusal, not a 500.

### B4.8 — Production deploy checklist
- Connection pool sizes tuned.
- Rate limits configured per tenant and per principal.
- Token budget enforcement.
- Anthropic + OpenAI fallback behavior under 5xx documented.
- Backup + restore rehearsed once for Postgres.
- Runbook for: stuck outbox, denied retrieval spike, AuthZ deny anomaly.

## Flutter deliverables (`yayaagenticweb`)

### F4.1 — Profile launch checklist UI
A pre-flight page for the chosen profile: green/red for each line item (tools schemas valid, AuthZ bindings present, knowledge source healthy, evals green, kill-switch reachable). Click "Launch" only when all green.

### F4.2 — Kill-switch panel
Operator can flip a profile or tool to "off" with a required reason. Audited. Live system shows the agent's in-character refusal in the playground when flipped.

### F4.3 — Rate-limit + budget panel
Configure per-tenant rate limits and token budgets. Live counters. Alerts when within 10% of cap.

### F4.4 — Load test dashboard
Live view of the load test in progress: TTFT, deny rate, retrieval latency, error rate. Useful during week 12.

### F4.5 — Operator runbook page
Renders the Markdown runbook from `docs/runbooks/M4-profile.md` inside the app with click-through to the relevant admin screens.

## Acceptance criteria

- [ ] Worked-example conversation (§14) reproduces verbatim against the real backend.
- [ ] Load test passes (50 RPS, p95 TTFT < 1.5s, p95 turn < 4s).
- [ ] Security review report shows zero critical findings open.
- [ ] All eval scenarios for the profile pass live-LLM run for ≥3 consecutive nights.
- [ ] Flutter pre-flight page goes green; "Launch" button enabled.
- [ ] On-call runbook reviewed by ops.

## Risks & open questions

- **Domain choice** — to be decided with product. Until decided, treat the §14 retail-customer example as the placeholder; do not skip this milestone over indecision.
- **Real LLM cost during load test** — budget the test against a staging key; consider Anthropic test endpoint or a shadow stub for non-LLM-sensitive runs.
- **OPA in production** — decision deferred from M1; revisit here based on policy complexity needed by the real profile.

## Exit checklist

- [ ] Profile deployed to staging end-to-end.
- [ ] Eval + security + load reports merged.
- [ ] Go/no-go meeting completed; if go, production deploy scheduled.
- [ ] M5 unblocked.
