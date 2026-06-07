package com.yayatechandinnovations.yayaagentic.demos.retail;

import com.yayatechandinnovations.yayaagentic.auth.Authorizer;
import com.yayatechandinnovations.yayaagentic.auth.AuthzContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Retail-customer-specific authorizer: the principal can only operate on
 * orders that belong to them. Activates only for the retail tool ids; for
 * everything else (including the engine's per-source AuthZ calls during
 * retrieval) it returns ALLOW so other chain members decide.
 * <p>
 * The lookup goes through {@link MockOrderStore}; in a real deployment the
 * same shape would call out to the order-management service. The interesting
 * property is that the LLM never sees this check — it proposes a tool_use
 * for {@code track_shipment(orderId=ORD-9001)}, the engine asks the chain,
 * the chain calls this authorizer, and a DENY short-circuits the dispatch.
 * The synthetic {@code tool_result(DENIED)} carries the user-safe reason
 * back into the LLM context where it gets paraphrased in Claude's voice.
 *
 * <p>Different user-safe vs audit reasons by design (§5.5):
 * <ul>
 *   <li>user-safe: "that order isn't on your account"</li>
 *   <li>audit:    "ownership check: principal=cust-1 order ORD-9001 owned by cust-2"</li>
 * </ul>
 */
@Component
@Order(15)
public class OrderOwnershipAuthorizer implements Authorizer {

    private static final Set<String> RETAIL_TOOLS = Set.of(
            "find_order", "track_shipment", "start_return");

    private final MockOrderStore orders;

    public OrderOwnershipAuthorizer(MockOrderStore orders) {
        this.orders = orders;
    }

    @Override
    public AuthzDecision authorize(Principal principal,
                                   PermissionRequirement requirement,
                                   Object args,
                                   AuthzContext ctx) {
        String toolId = toolIdOf(ctx);
        if (toolId == null || !RETAIL_TOOLS.contains(toolId)) {
            return AuthzDecision.Allow.none();
        }

        String orderId = orderIdOf(args);
        if (orderId == null || orderId.isBlank()) {
            // The schema validator already requires orderId, so this is a
            // defensive guard — if the validator changes, deny rather than
            // accidentally calling the tool with an empty id.
            return new AuthzDecision.Deny(
                    "I need an order id to look that up — could you share it?",
                    "ownership check: missing orderId arg for tool=" + toolId);
        }

        var order = orders.find(orderId);
        if (order.isEmpty()) {
            // Unknown order: treat as a denial so a malicious enumeration
            // attempt can't probe ids. The user-safe reason is still
            // generic; "we don't say which".
            return new AuthzDecision.Deny(
                    "that order isn't on your account",
                    "ownership check: order " + orderId + " not found");
        }

        String principalSubject = principal == null ? null : principal.subject();
        String ownerSubject = order.get().customerId();

        if (principalSubject == null) {
            return new AuthzDecision.Deny(
                    "your session doesn't appear linked to a specific account",
                    "ownership check: principal subject is null for tool=" + toolId);
        }
        if (!ownerSubject.equalsIgnoreCase(principalSubject)) {
            return new AuthzDecision.Deny(
                    "that order isn't on your account",
                    "ownership check: principal=" + principalSubject
                            + " order=" + orderId + " owned by " + ownerSubject);
        }
        return AuthzDecision.Allow.none();
    }

    private static String toolIdOf(AuthzContext ctx) {
        if (ctx == null || ctx.attributes() == null) return null;
        Object v = ctx.attributes().get("toolId");
        return v == null ? null : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static String orderIdOf(Object args) {
        if (args instanceof Map<?, ?> m) {
            Object v = ((Map<String, Object>) m).get("orderId");
            return v == null ? null : v.toString();
        }
        return null;
    }
}
