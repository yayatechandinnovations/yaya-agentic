# apps/ — shippable integrations

This directory holds **standalone applications we ship *with* yaya-agentic but that are NOT part of the yaya-agentic core.**

yaya-agentic (repo root `./`) is a **framework**: profile-driven, auth-aware conversational agents built from Personality / Profile / Auth primitives and the SPI inventory in `CLAUDE.md`. It must stay domain-neutral — it knows how to run an agent, not how to sell a soap, book a room, or track a shipment.

Applications in `apps/` are **domains**. Each one:

- is a **separate, independently deployable application** — its own build, database/migrations, container, and release cadence;
- **does not** import yaya-agentic internals, and yaya-agentic **does not** import it;
- integrates with yaya-agentic **only through the published integration seams** — chiefly HTTP tools (`ToolHandlerRef.Http` + `HttpToolSpec`, design §5.3), explicit `AuthForwarding` (§5.4/§5.5), and engine-emitted `TurnEvent.UiHint` frames (§6.1/§9);
- can run **without** yaya-agentic (e.g. as a plain REST service), and yaya-agentic can run **without** it.

The test for whether something belongs in `apps/` vs. the core: *if it's a business domain, it's an app; if it's a way to build agents, it's the framework.* When in doubt, keep the core thin and push the domain into an app.

Two flavors live here: **leaf domain apps** (e.g. yaya-sales — a business domain) and **platform-service apps** (e.g. yaya-identity — shared infrastructure other apps depend on). Both obey the rules above; a platform-service app just happens to be a dependency of other apps rather than a leaf.

## Applications

| App | What it is | Design doc |
|---|---|---|
| [`yaya-sales/`](yaya-sales/) | Leaf domain app — standalone multi-tenant **sales & commerce** (catalog, ledgered inventory, pricing, cart, orders, tenders/PSP + COD, fulfillment & delivery, courier fleet). Integrates as commerce HTTP tools for a profile. | [`yaya-sales/docs/design/yaya-sales-design.md`](yaya-sales/docs/design/yaya-sales-design.md) |
| [`yaya-identity/`](yaya-identity/) | Platform-service app — shared **identity provider & authorization service** (orgs, users, groups, roles, policies; OAuth2/OIDC issuer + a policy-decision API). Apps delegate AuthN/AuthZ to it; yaya-agentic trusts it as an OIDC issuer with zero new agent code. | [`yaya-identity/docs/design/yaya-identity-design.md`](yaya-identity/docs/design/yaya-identity-design.md) |

> **History:** commerce began life fused into the agent core (`com.yayatechandinnovations.yayaagentic.commerce.*`). yaya-sales exists to un-fuse it — see the yaya-sales design doc §12 (Migration) for what moves out of the core and in what order.
