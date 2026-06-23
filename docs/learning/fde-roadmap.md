# FDE Roadmap — using `yaya-agentic` as the learning vehicle

> Audience: a senior Software Engineer transitioning to **Forward Deployed Engineer** (FDE) roles at AI infra / applied-AI companies (Anthropic, OpenAI, Palantir-adjacent, applied-AI startups).
>
> Premise: an FDE embeds with a customer, scopes a problem, prototypes against an LLM platform, and lands a production-shaped system. The bar is *applied AI + enterprise integration + product judgment*. This repo is a near-perfect vehicle: it is a **profile-driven, auth-aware, tool-using, RAG-enabled conversational-agent framework** with operator tooling. That's the same shape as ~80% of real FDE engagements.
>
> This document maps **the concepts you need** to **the place in this repo where they're already implemented (or scoped)**, and lists what's missing for full FDE coverage.

---

## 1. What an FDE actually does (so the map makes sense)

A useful working definition: an FDE is a senior engineer who is **billable, embedded, and outcome-owned**. The deliverable is "this customer is live and seeing value," not "this PR is merged." Concretely:

| FDE activity | What "good" looks like | Where in this repo |
|---|---|---|
| **Scoping** a use case with a customer (workshops, discovery) | Frame problem → propose minimum viable agent → de-risk the unknowns | Not in code — see §4 below |
| **Prototyping** against the LLM API | A working tool-using agent in a notebook in ≤1 day | M0 + `claude-api` skill |
| **Productionizing** the prototype | Streaming, auth, observability, eval, rollback | M1 → M3 |
| **Integrating** with the customer's systems | SSO, internal APIs, data stores, message buses | M1 (HTTP tools, Authenticator/Authorizer SPIs), M2.5 (RAG sources), M2.8 (tenant registry) |
| **Trust & safety** at enterprise scale | Prompt-injection mitigations, redaction, audit trails | Design §12, M1 recorder, M2.5 access-controlled sources |
| **Evaluation** the customer trusts | Per-profile scenarios, regression detection, latency/cost dashboards | M3 |
| **Handoff** to the customer's engineering org | Clean SPIs, docs, runbooks | M5 + this doc |

The repo covers the **technical 80%**. The remaining 20% is the customer-facing, scoping, communication, and cost/packaging work — addressed in §4.

---

## 2. Why this codebase is a good FDE vehicle

It's not a CRUD app dressed up with an LLM call. It is structured around the exact tensions an FDE feels in week 2 of a real engagement:

1. **The LLM is the cheapest part to wire up; *trust* is the expensive part.** This repo enforces that from line 1: every tool call routes through schema validation → an `Authorizer` chain → typed dispatch. The LLM is never trusted (design §12). FDEs ship this. SWE tutorials usually don't.
2. **Profiles are the deployment unit.** A real customer has 5–10 distinct user roles. The `Profile` abstraction (design §5.2) is what lets you stamp them out without forking the agent.
3. **Auth is a real SPI, not a hand-wave.** `Authenticator` + `Authorizer` + `AuthzDecision(Allow|Deny(userSafe, audit))` (design §5.4–5.5) is what enterprise integrations actually require. Internalize the *two-reason denial*.
4. **RAG is treated as access-controlled retrieval, not "stuff some chunks in the prompt."** Design §5.8 makes knowledge sources subject to their own `AccessRequirement`, with provenance. This is the difference between a demo and a deployable system.
5. **The conversation is a first-class persistent artifact** (`ConversationRecorder`, §5.9). FDEs get asked "what did the bot tell our customer last Tuesday?" — this is the answer.
6. **Eval is a milestone, not an afterthought** (M3). The customer's "is it working?" question is unanswerable without this.
7. **The operator console exists** (`yayaagenticweb`). FDEs are repeatedly surprised by how much of the value lives in *the tools the customer's ops team uses to tune the agent*, not the agent itself.

---

## 3. The learning map — 8 phases, anchored to the repo

Each phase: **(a)** the concept, **(b)** why FDEs specifically care, **(c)** what to read, **(d)** what to build.

> Read the **design doc section** before the milestone file before the code. The design doc tells you *why*; the milestone tells you *what shape*; the code tells you *how*. Skipping straight to code at this level of abstraction will frustrate you.

---

### Phase 0 — Orient yourself (½ day)

**Goal:** understand the shape before touching anything.

- Read in this order:
  1. `docs/design/yaya-agentic-design.md` §1–4 (summary, goals, glossary, architecture)
  2. `docs/milestones/README.md` (the dependency graph)
  3. `CLAUDE.md` (project conventions + non-negotiables)
  4. `src/main/java/.../core/` — skim the records: `Ids`, `Principal`, `Session`, `Turn`, `IntentFrame`, `PermissionRequirement`, `ExecutionContext`
- Output: a one-page sketch (whiteboard / paper) of how a single user message becomes a streamed response. You should be able to name the SPI that handles each arrow.

**FDE relevance:** the ability to *read a system into your head from its design doc + entry points* is the single most important FDE skill. You will do it under time pressure on every engagement.

---

### Phase 1 — LLM mechanics: tool use, streaming, prompt caching (3–5 days)

**Concept:** how Claude actually works as an *agent* (not just a chatbot). The `tools` parameter, JSON-schema arguments, multi-turn tool-use loops, streaming events, the prompt-cache `cache_control` markers.

**Why FDEs care:** every customer demo starts here. If you can't write a tool-using loop in a notebook in 30 minutes, you can't sell the next 6 weeks of work. Prompt caching in particular is *the* lever for making enterprise agents affordable — under-used by SWE generalists.

**What to read/use:**
- The `claude-api` skill in this harness (slash command `/claude-api` or just trigger it by editing Anthropic SDK code) — it has the current best practices for caching, tool use, streaming.
- Anthropic docs: tool use, streaming, prompt caching, extended thinking.
- M0 milestone (`docs/milestones/M0-skeleton.md`) — the smoke test deliverable.
- This repo's `engine/` package once it exists (currently scaffolded).

**What to build (exercise):**
- A standalone Python notebook (outside this repo) that calls Claude with **two tools** (e.g. `get_balance`, `transfer_funds`), streams the response, prints each tool-use and tool-result event, and re-issues the second turn with the tool result. Then re-run it with `cache_control` markers on the system prompt and observe the `cache_read_input_tokens` jump.
- Then map every concept in your notebook to a place in this repo: which SPI owns "tool definition," which owns "send the result back to the model," which owns "stream the token."

**Anti-pattern to avoid:** writing a thin wrapper over `client.messages.create(...)` in Python and calling it "the agent." The whole point of the repo's structure is that *the agent is not a function call* — it's the orchestration of auth, tools, memory, and recording around the LLM call.

---

### Phase 2 — The trust model: AuthN, AuthZ, and the two-reason denial (4–6 days)

**Concept:** `Authenticator` produces a `Principal`. `Authorizer` (chained) returns `AuthzDecision.Allow` or `AuthzDecision.Deny(userSafeReason, auditReason)`. **Every** tool call and knowledge read passes through it. Tools never check policy themselves.

**Why FDEs care:** this is the single most under-appreciated concept in applied AI. Enterprise customers will not deploy an agent that can call internal APIs without a defensible authorization story. The two-reason denial pattern (one paraphrased to the user, one logged verbatim) is what passes a security review.

**What to read:**
- Design §5.4 (Authentication), §5.5 (Authorization), §12 (Safety, Guardrails, Trust Boundaries)
- M1 milestone — the SPI implementations
- `src/main/java/.../auth/`
- The non-negotiables list in `CLAUDE.md` (especially #1, #2, #3, #6)
- `docs/design/operator-auth-design.md` (sibling design doc — operator JWT model)

**What to build:**
- Implement a toy `Authorizer` that denies any tool call requiring a `customer_owns(account_id)` permission unless the principal's `subjectId` matches the account's owner. Write the deny with both a friendly `userSafeReason` ("That account isn't yours.") and an audit `auditReason` (`principal=alice subjectId=u123 attempted=getBalance account=A456 ownerOf(A456)=u789`).
- Then write a test that asserts the friendly reason is in the user-facing turn and the audit reason is in the recorder log — *and they are different strings*.

**FDE habit to internalize:** *the LLM proposes; the Authorizer disposes.* Repeat it. Use it in customer architecture reviews. It's the line that wins trust.

---

### Phase 3 — Profiles, capabilities, and the resolver chain (3–4 days)

**Concept:** a `Profile` pins a system prompt, a list of `Capability`s (each backed by tools), knowledge sources, and an auth binding. The `ProfileResolver` chain (Explicit → Identity → Channel → Fallback) decides which profile is in play for a given session.

**Why FDEs care:** customers always have more than one user type. The natural temptation is to fork the agent ("retail bot," "merchant bot"). Profiles let you ship one runtime with N stamped role-bundles. This is exactly the shape of a multi-role enterprise deployment.

**What to read:**
- Design §5.2 (Profile), §5.3 (Capability & Tool), §5.6 (Resolver chain)
- M1 milestone (resolver implementations)
- `src/main/java/.../profile/`

**What to build:**
- Define two profiles (`retail-customer`, `support-agent`) with overlapping but distinct capability sets. Show that a `retail-customer` session cannot dispatch a `support-agent`-only tool *even if the LLM hallucinates the call* (because the tool is never listed in the prompt at all — non-negotiable #2).
- Then implement an `IdentityProfileResolver` that picks `support-agent` if the principal carries a `role=support` claim, else falls through to the explicit-or-fallback path.

---

### Phase 4 — Tools as the integration surface (Bean vs HTTP) (4–6 days)

**Concept:** `ToolHandlerRef` is sealed: `Bean(beanName)` for in-process Java and `Http(HttpToolSpec)` for external services. `HttpToolSpec` includes `AuthForwarding ∈ {NONE, PRINCIPAL_TOKEN, SERVICE_TOKEN}` — explicit per spec, never silent.

**Why FDEs care:** real engagements never have all the data in one Java process. The HTTP tool transport is how you reach the customer's internal APIs, Salesforce, ServiceNow, a vector DB, an ERP. The explicit `AuthForwarding` enum is the *audit-passable* answer to "how does the bot get a token to call our payroll service?"

**What to read:**
- Design §5.3 (Capability & Tool)
- `docs/design/tool-url-resolution-design.md` (sibling — path-only HTTP tools)
- M2.8 milestone (tenant-registry hosting; this is **🟩 Done** in this repo — read the implemented pattern)
- `src/main/java/.../tool/`

**What to build:**
- A Bean tool: `GetBalance(accountId) → Money`. Wire it through `ToolHandlerRef.Bean`. Make sure the LLM only sees the tool when the profile lists it.
- An HTTP tool: same shape, but the handler hits a fake JSON endpoint (use [httpbin.org](https://httpbin.org/) or a local mock). Forward the principal's token. Then re-deploy it as `AuthForwarding.SERVICE_TOKEN` and watch the audit log show the change. Talk yourself through *which customer scenario each choice is appropriate for*.

**FDE habit:** before writing any custom code, ask: "Can this be an HTTP tool against the customer's existing service?" The answer is usually yes. This is the difference between a 2-week engagement and a 2-month one.

---

### Phase 5 — The conversation engine: intent, memory, streaming, denial UX, prompt caching (5–7 days)

**Concept:** the `ConversationEngine` glues everything together. `IntentTracker` handles the parked-intent stack. `PromptBuilder` composes Personality → Profile → variable-suffix in that order specifically so the prefix stays cache-hot (design §7 + non-negotiable item in `CLAUDE.md`). `TurnEvent` is the sealed stream type: `Token | ToolCall | ToolResult | Citation | UiHint | End`.

**Why FDEs care:** this is where customer demos live or die. Streaming UX, the way a denial is paraphrased back to the user, the way a quick-reply renders — these are the things the customer's PM remembers. Also, **prompt caching is the difference between a $0.30 turn and a $0.04 turn** at scale. FDEs are responsible for unit economics.

**What to read:**
- Design §5.7 (Session & Memory), §6 (Lifecycle), §7 (Prompt Composition)
- M2 milestone — intent tracker, denial UX, working memory, streaming UI hints, prompt caching
- `src/main/java/.../engine/`

**What to build:**
- Implement a denial UX flow: the user asks for someone else's data → `Authorizer` denies → the engine emits a turn that paraphrases `userSafeReason` ("I can only help with your own accounts.") *plus* a `UiHint` quick-reply suggesting "View my accounts." The audit reason should be in the recorder, not the user-visible turn.
- Then measure your turn cost with and without `cache_control` markers on the system prompt. Write the per-turn cost difference down. This is the number a customer's CFO will ask about.

---

### Phase 6 — RAG done right: access-controlled retrieval with citations (5–7 days)

**Concept:** knowledge sources are first-class objects with their own `AccessRequirement` (design §5.8). Retrieval merges hybrid scores (vector + keyword), filters by access *silently* (existence is not leaked — non-negotiable #7), and returns chunks with provenance so the engine can render `Citation` events.

**Why FDEs care:** customers will not ship RAG without citations and access control. "The bot leaked our acquisition memo to a contractor" is a fireable offense. The pattern of *drop-on-deny without telling the LLM the source existed* is what passes review.

**What to read:**
- Design §5.8 (Knowledge Sources & Retrieval)
- M2.5 milestone — hybrid retrieval, citations, access-controlled sources
- `src/main/java/.../knowledge/`

**What to build:**
- Two `KnowledgeSource`s: `public-faq` (everyone), `internal-policies` (`role=employee` only). Ingest a handful of markdown chunks each. Query the retriever as a `customer` principal and assert that `internal-policies` chunks are *not in the result set and not mentioned in any way*. Then query as an `employee` and assert both surface.
- Wire the result into the engine so the assistant turn includes `Citation` events with the source URI + offset. This is what the customer's compliance team will inspect.

**FDE habit:** every RAG answer must be accompanied by a citation. Demo-only RAG without citations is malpractice at this point.

---

### Phase 7 — Eval & observability: making "is it working?" answerable (5–7 days)

**Concept:** per-profile scenario suites, OTel traces of every turn (LLM call, tool calls, retrieval calls), replay of recorded sessions against a new model or prompt. M3 milestone.

**Why FDEs care:** every customer asks "is this working?" and "is the new version better?" Without an eval harness, your answer is vibes. With one, your answer is a regression diff. This is also how you defend a model upgrade (Sonnet 4.5 → 4.6 → 4.7) to a customer.

**What to read:**
- Design §13 (Observability & Evaluation)
- M3 milestone — eval harness, OTel traces, per-profile scenarios, replay

**What to build:**
- A scenario file: 10 turns with expected behaviors (a tool that should be called, an authorization that should be denied, a citation that should appear). Run the suite against your current build. Then deliberately introduce a regression (e.g. remove the system prompt rule about treating tool results as data) and watch the suite catch it.
- Plumb one OTel trace through a complete turn so you can see the LLM call, the authorizer decision, and the tool call as nested spans.

---

### Phase 8 — End-to-end on a real domain + security review (1–2 weeks)

**Concept:** assemble Phases 1–7 into one believable vertical (e.g. a banking-support agent, a clinic-intake agent, a developer-tools agent). Pass a security review.

**Why FDEs care:** this is the closest analogue to a real engagement. Customers don't buy capabilities — they buy *one believable end-to-end story*. Your portfolio piece is one such story.

**What to read:**
- M4 milestone — first real profile, ownership AuthZ, load test, security review
- Design §11 (Extensibility — adding a new profile), §14 (Worked Example)
- The `/security-review` skill in this harness

**What to build:**
- Pick a domain. Define 1 profile, 5–8 capabilities (mix of Bean and HTTP tools), 2 knowledge sources (1 public, 1 access-controlled), an `Authorizer` chain that includes ownership checks, and a scenario suite with at least 20 cases (happy paths, denials, ambiguous intent, prompt-injection attempts in retrieved content).
- Run `/security-review` against your branch and address every finding.
- Write a one-page customer-facing architecture doc. Include the trust boundaries diagram and the unit-cost-per-turn estimate. *This is your interview portfolio piece.*

---

## 4. What's missing from this repo that an FDE also needs

This repo is technical-spine excellent. These are the FDE-shaped skills it doesn't (and shouldn't) cover — learn them alongside.

### 4.1 Customer-shaped skills (the actual 20%)

- **Discovery & scoping.** Practice running a 60-minute workshop that turns "we want AI" into a one-paragraph use case + a list of integrations + a definition of done. Read Palantir FDE write-ups, watch Anthropic's applied-AI talks. Build a personal "first-week-checklist" template.
- **Technical writing for non-technical execs.** One-page architecture briefs, cost models on a single slide, risk registers. FDEs write more docs than code.
- **Demo hygiene.** A live demo that fails graceful beats a video that demos perfect. Practice running this repo's playground (`yayaagenticweb`) against a flaky network. Learn what to say when a tool call fails on stage.
- **Pricing & packaging conversation.** Per-turn unit cost (you computed it in Phase 5), per-seat economics, what to charge for an integration vs. usage. Most SWEs avoid this; FDEs can't.

### 4.2 Technical surface not in this repo

- **Quick prototyping in Python.** Real engagements start in a Jupyter notebook (LangChain or raw `anthropic` SDK), not in a Spring Boot app. Build a parallel `notebooks/` folder *not in this repo* where you spike use cases in Python before deciding if they deserve the full framework.
- **Common enterprise connectors.** Slack, Salesforce, ServiceNow, Confluence, Google Drive, SharePoint, Jira, Snowflake. Build at least one HTTP tool against each (you can use their sandbox APIs). FDE engagements always include 2–3 of these.
- **Document ingestion at scale.** This repo does retrieval but doesn't deeply cover OCR, PDF parsing, table extraction. Pick a real PDF corpus (10-K filings, medical PDFs) and learn the ingestion stack (Unstructured, LlamaParse, Claude vision for tables).
- **Voice and multi-modal.** Increasingly common in FDE work. Add a voice channel to your Phase 8 vertical (Twilio + a streaming voice model). Add document upload (Claude vision → structured extraction).
- **Model routing & fallback.** Sonnet for simple turns, Opus for hard ones, Haiku for cheap classification, batch API for non-interactive workloads. Build a `ModelRouter` SPI as an exercise (not in current scope) — explicit cost/latency/quality trade-off.
- **Guardrails beyond auth.** PII detection, jailbreak detection, output filtering. Wire one library (e.g. Presidio for PII) into your Phase 8 build as a pre-record redaction step.
- **Data pipelines.** Sometimes the bot is the easy part and the customer's data isn't ready. Be comfortable with batch ETL (dbt, Airflow), CDC, and basic warehouse work (Snowflake/BigQuery). FDEs often unblock the data before they ship the agent.
- **Eval methodology.** The repo has a harness; it doesn't teach *how to design an eval set*. Read up on Anthropic's eval guidance and synthetic data generation. Practice generating adversarial cases.
- **SSO / IdP integration.** Okta, Auth0, Azure AD. The `Authenticator` SPI is the hook; you need to know what plugs into it in the real world. Build a Phase 8 variant that uses real Okta dev tier.

### 4.3 Soft skills (do not skip)

- **Async written communication.** Customers will judge you on your Slack messages and one-pagers more than your code. Practice writing tight status updates.
- **Saying no.** "We could build that in 3 weeks, but the better answer for your problem is X" is the most valuable sentence an FDE owns. Practice it.
- **Knowing when to leave clean.** Phase 8 should end with a runbook, a handoff doc, and a list of follow-ups for the customer's engineering team. Practice the handoff narrative.

---

## 5. What's overkill if your goal is pure FDE learning

Not bad — it's *good*. But if you're learning the role, you can skim these without losing the plot:

- **The full Flutter admin app (`yayaagenticweb`).** It's a fantastic showcase of operator tooling, and FDEs *should* know that operator UI usually drives more value than the agent itself. But for *learning*, you don't need to ship the same UI. A Streamlit / Gradio / lightweight React equivalent against the same backend SSE API will teach the same lessons in 1/5 the time. Plan to *read* the Flutter app's structure but build your own slim operator UI for your portfolio piece.
- **M5 (multi-tenant admin).** Important for a product company; less relevant for FDE engagements, which are typically single-tenant per deployment. Read the design and stop. Come back if you join a product team.
- **The deep formal SPI taxonomy.** In real engagements you will sometimes write *more direct code* than this repo prescribes — because the customer's timeline doesn't allow for full SPI separation on day 7. Internalize the patterns; don't make every prototype this formal. *The patterns are insurance; the prototype is speed.*
- **The Java/Spring stack itself.** If you're already strong here, fine. If you're not, do not let "I should learn Spring Boot first" gate your FDE prep — many FDE engagements are Python/TypeScript-first. Use this repo for *concept* learning even if your customer code is Python.

---

## 6. Suggested 12-week calendar

Tune to your own pace. The phases stack; don't skip phase 2.

| Week | Focus | Output |
|---|---|---|
| 1 | Phase 0 + Phase 1 (LLM mechanics) | A notebook with streaming + tool use + caching, with measured token costs |
| 2 | Phase 2 (trust model) | Working `Authenticator` + chained `Authorizer` with two-reason denials, plus a 1-page note on *why* this matters for enterprise |
| 3 | Phase 3 (profiles) | Two profiles with non-overlapping capabilities, tested for cross-profile tool isolation |
| 4 | Phase 4 (tool transports) | One Bean tool, one HTTP tool, all three `AuthForwarding` modes exercised |
| 5–6 | Phase 5 (engine) | End-to-end streamed turn, denial UX, quick-reply UI hints, measured cache hit rates |
| 7 | Phase 6 (RAG) | Two access-controlled sources, citations rendered, leakage test passed |
| 8 | Phase 7 (eval/obs) | 20-case scenario suite, one OTel trace, regression caught on purpose |
| 9–10 | Phase 8 build | Domain pick + production-shaped vertical |
| 11 | Phase 8 finish | Security review, runbook, handoff doc, one-page architecture brief |
| 12 | Portfolio + interviews | Demo recorded, code public, write up "what I'd do differently in week 2 of an engagement" |

While you're doing the above, **in parallel** (10–20% time):

- Spike each enterprise connector from §4.2 as a one-day HTTP tool. By week 12 you should have touched Slack, Salesforce/ServiceNow, Drive/SharePoint, Snowflake, and an SSO IdP at least once each.
- Read one FDE-shaped writeup per week (Palantir engineering blog, Anthropic case studies, OpenAI cookbook, applied-AI startup engineering blogs).
- Write one short post per phase about what surprised you. Recruiters and hiring managers read these.

---

## 7. Interview-prep mapping

When an FDE interview asks *"how would you deploy a tool-using agent for a regulated enterprise customer?"* — these are the lines you want to be able to say:

- "Every tool call routes through schema validation, then a chained authorizer, then typed dispatch. The LLM never enforces policy." → Design §12 + your Phase 2 build.
- "Denials carry two reasons: a paraphrasable user-safe reason and an audit reason. They're always different." → Design §5.5 + your Phase 2 build.
- "Knowledge sources have their own access requirements; ineligible sources are dropped before they reach the model — we never tell the LLM a source existed." → Design §5.8 + your Phase 6 build.
- "We cache the personality + profile prefix and only vary the per-turn suffix; that takes turn cost from ~$0.30 to ~$0.04 at our volume." → Design §7 + your Phase 5 measurements.
- "Every conversation is recorded with provenance and is replayable against a new model — that's how we defend a model upgrade to compliance." → Design §5.9 + M3 + your Phase 7 build.
- "HTTP tool auth forwarding is explicit per spec — `NONE` / `PRINCIPAL_TOKEN` / `SERVICE_TOKEN`. Default is never to forward." → Design §5.3 + your Phase 4 build.

Each line is backed by a system you actually built. That's what separates an FDE candidate from a SWE candidate.

---

## 8. Resources

- **In-repo:**
  - `docs/design/yaya-agentic-design.md` — design source of truth
  - `docs/milestones/` — implementation-ready milestone plans
  - `docs/design/operator-auth-design.md`, `tenant-registry-design.md`, `tool-url-resolution-design.md` — sibling design docs
  - `CLAUDE.md` — conventions + non-negotiables
- **External (start here):**
  - Anthropic docs — tool use, prompt caching, streaming, extended thinking, batch API
  - Palantir engineering blog — FDE-shaped writeups (older but foundational)
  - Applied-AI case studies from Anthropic / OpenAI customer pages — the public arc of real engagements
- **In this harness:**
  - `/claude-api` skill — best-practice Claude API integration
  - `/security-review` skill — for Phase 8
  - `/code-review ultra` — for the final polish before your portfolio commit

---

## 9. One closing principle

> **The job is not to build an agent. The job is to make a customer trust an agent enough to ship it.**

Every concept in this repo — profiles, authorizers, two-reason denials, citations, recorders, evals — is a downstream consequence of that one principle. Anchor your learning to it. If a feature in this repo doesn't obviously serve that principle, you haven't understood the feature yet; re-read the design section.

Good luck.
