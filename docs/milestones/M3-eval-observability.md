# Milestone M3 — Eval & Observability

| | |
|---|---|
| **Goal** | Every prompt and profile change has a quantitative regression signal. Every turn is traceable end-to-end. Replay works against recorded sessions without re-issuing real side effects. |
| **Timeline** | Weeks 10–11 (design §17 — M3) |
| **Status** | 🟦 Planned |
| **Depends on** | M2, M2.5 |
| **Design refs** | §13 observability & evaluation · §5.9 replay · §16 q14 replay diff |

## Scope (IN)

- Eval harness: scenario format, runner, assertion library, CI integration.
- Per-profile eval scenarios for every shipped profile.
- OpenTelemetry traces: `intent.update`, `prompt.build`, `llm.call`, `tool.authorize`, `tool.execute`, `retrieval`, `recorder.write`.
- Metrics: turns/session, tool calls/turn, AuthZ deny rate, denial-to-resolution rate, time-to-first-token, tokens in/out, cache hit ratio, per-tool latency, retrieval latency + recall@k from evals.
- Replay mode (`ReplayMode.RECORDED_RESULTS`) with tool dispatch interception.
- Replay diff: when LLM's plan diverges from the recorded one, surface a structured diff.
- Dashboards: a starter Grafana board and a Prometheus scrape endpoint.
- Admin "session replay" UX in the Flutter app.

## Out of scope (NOT here)

- Live A/B framework (post-v1).
- Model fine-tuning loops.
- Customer-facing analytics.

## Backend deliverables

### B3.1 — Scenario format
YAML-based scenario format committed to `src/test/resources/evals/`:
```yaml
id: retail.list-other-transactions
profile: retail-customer@v1
principal:
  subject: u_42
  scopes: [user.read]
  claims: { accounts: [1234] }
turns:
  - user: "Show me transactions for account 998877"
    expect:
      tool_called: list_transactions
      authz_decision: DENY
      assistant_contains_any: ["only show transactions for accounts linked"]
      assistant_does_not_contain: ["principal=", "audit_reason"]
  - user: "Yes show 1234, last 30 days"
    expect:
      tool_called: list_transactions
      authz_decision: ALLOW
      tool_args: { account_id: 1234, range: "30d" }
```

### B3.2 — `EvalRunner`
JUnit 5 dynamic-test source that loads every YAML in `evals/` and runs them against:
- a live or stubbed LLM (config flag),
- a stub tool registry that returns scripted results per scenario.
The runner records full traces and produces an HTML report.

### B3.3 — Assertions library
`tool_called`, `tool_not_called`, `tool_args` (subset match), `authz_decision`, `assistant_contains_*`, `intent_label`, `parked_intent_present`, `citation_includes`, `quick_replies_contain`, `tokens_under`. Each assertion produces a structured failure with the offending turn slice.

### B3.4 — OpenTelemetry instrumentation
Manual spans at the SPI boundaries listed in §13.1. PII redaction for attributes is driven by tool/knowledge schema tags. Exporter config supports OTLP HTTP.

### B3.5 — Metrics
Micrometer counters / timers / gauges per §13.2. Exposed via Actuator `/actuator/prometheus`.

### B3.6 — Replay mode
`ConversationEngine.replay(sessionId, ReplayOptions)`. Internally:
- Loads the recorded session via `ConversationRecorder.loadSession(verbatim)`.
- Dispatches a new turn through the engine with `ToolDispatchInterceptor` returning the recorded result for matching `(toolId, args)` keys.
- Unmatched dispatches either return a synthetic "no recorded result" or pause for operator review (configurable).
- Emits a `replay_diff` event per turn with: `tools_added`, `tools_removed`, `tools_arg_changed`, `assistant_text_diff` (token-level).

### B3.7 — Replay diff structured output
Diff is JSON, not just text. Schema versioned and committed at `docs/replay-diff-schema.json`. Used by Flutter to render side-by-side.

### B3.8 — Starter Grafana dashboard JSON
Committed at `docs/observability/grafana-dashboard.json`. Panels: TTFT, cache hit ratio, AuthZ deny rate over time, retrieval latency, top tools by latency.

### B3.9 — Eval CI integration
A GitHub Actions job runs `EvalRunner` against the stubbed LLM on every PR. Live-LLM eval is a nightly job (cost-bounded).

## Flutter deliverables (`yayaagenticweb`)

### F3.1 — Session search & detail
- Search sessions via `GET /v1/admin/sessions`.
- Detail page: full turn-by-turn timeline with role icons, tool calls, tool results, citations, audit links.

### F3.2 — Replay UX
On a session detail page, "Replay" button opens a side-by-side view: recorded vs current. The replay-diff schema renders inline (added tool calls highlighted, removed in strikethrough, arg diffs as JSON deltas). The current run streams live via SSE.

### F3.3 — Eval results browser
A page that lists eval runs (CI-fetched from a configured URL or local upload), each with a tree of scenarios, pass/fail badges, and trace links. A scenario failure expands to show the offending turn and assertion message.

### F3.4 — Trace viewer
Embedded trace viewer per turn (Jaeger-style waterfall, lightweight implementation). Click a span to see attributes.

### F3.5 — Observability quick links
Top-bar quick-link panel: latest metrics snapshot (TTFT p50, cache hit ratio, deny rate). Links out to Grafana when configured.

## Acceptance criteria

- [ ] Every shipped profile has ≥3 eval scenarios covering: happy path, denial, intent pivot, knowledge-grounded answer (if profile has sources).
- [ ] PR CI runs the stubbed-LLM eval suite in under 2 minutes; nightly live-LLM run completes under 15 minutes.
- [ ] A recorded session can be replayed in the Flutter UI; a structurally different plan is visibly diffed.
- [ ] Trace for a single turn shows all SPI-boundary spans with correct parent-child relationships.
- [ ] Grafana board renders against a live Prometheus scrape; deny-rate alert rule documented (not wired in M3).

## Risks & open questions

- **Eval flakiness** — LLM nondeterminism. Stub by default; sample live runs nightly; assertions use `_contains_any` rather than exact match.
- **Replay drift** — recorded results may be stale; surface staleness in the diff view.
- **Trace volume** — sample at 100% in dev, configurable in prod.

## Exit checklist

- [ ] Eval suite running in CI as a required check.
- [ ] One worked-example session is replayed in the Flutter UI with a meaningful diff (intentionally introduce a profile prompt change to demonstrate).
- [ ] M4 unblocked.
