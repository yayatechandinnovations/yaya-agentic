package com.yayatechandinnovations.yayasales.platform.auth;

import com.yayatechandinnovations.yayasales.platform.Ids.AgentId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenantRef;

import java.util.List;

/**
 * The four caller classes (design §10, §15), each with its own auth posture:
 *
 * <ul>
 *   <li>{@link Operator} — a merchant/console user, JWT from the trusted OIDC
 *       issuer; hits {@code /v1/sales/admin/*}.</li>
 *   <li>{@link Service} — the yaya-agentic platform, HMAC service token
 *       ({@code X-Yaya-Service-Token}); required on {@code /v1/sales/agent/*}.</li>
 *   <li>{@link Shopper} — a verified end-user principal, JWT forwarded by the
 *       agent's explicit {@code PRINCIPAL_TOKEN} auth-forwarding (#6); optional
 *       on the agent API, attributes carts/orders to a subject.</li>
 *   <li>{@link Courier} — a delivery agent holding a yaya-sales-issued token
 *       (§8.9/§10.4, lands S5); scoped to one store, least privilege.</li>
 * </ul>
 */
public sealed interface Caller {

    record Operator(String subject, String email, List<String> roles) implements Caller {}

    record Service(TenantRef tenant, String onBehalfOf, List<String> scopes) implements Caller {}

    record Shopper(String subject, String issuer) implements Caller {}

    record Courier(AgentId agent, StoreId store) implements Caller {}
}
