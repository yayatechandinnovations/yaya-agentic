package com.yayatechandinnovations.yayaagentic.commerce.tool;

import com.yayatechandinnovations.yayaagentic.commerce.Cart;
import com.yayatechandinnovations.yayaagentic.commerce.CommerceConfig;
import com.yayatechandinnovations.yayaagentic.commerce.CommercePayloads;
import com.yayatechandinnovations.yayaagentic.commerce.cart.ManagedCart;
import com.yayatechandinnovations.yayaagentic.commerce.payment.CheckoutOutcome;
import com.yayatechandinnovations.yayaagentic.commerce.payment.CheckoutRequest;
import com.yayatechandinnovations.yayaagentic.commerce.payment.PaymentProvider;
import com.yayatechandinnovations.yayaagentic.commerce.payment.PaymentProviderRouter;
import com.yayatechandinnovations.yayaagentic.commerce.payment.onboarding.MerchantPspAccountService;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.persistence.CommerceOrderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.CommerceOrderRepository;
import com.yayatechandinnovations.yayaagentic.tool.ToolException;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code checkout} — the money-mover. It's {@code confirmable}, so the engine
 * raises the confirm UiHint ("Place order — 2 items, $25.98?") before this runs;
 * only after the shopper confirms does it open a checkout with the
 * {@link PaymentProvider} on the <b>authoritative cart total</b> (the model never
 * touches the amount, #1). It emits a {@code checkout_result} UiHint for the
 * client to complete payment, records the order, and clears the cart only when
 * the outcome is terminal — a hosted-page / intent flow stays PENDING until the
 * payment is actually completed (a webhook flips it later). See §11.
 */
@Component(CommerceTools.CHECKOUT_BEAN)
public class CheckoutToolHandler implements ToolHandler<Map<String, Object>, Map<String, Object>> {

    private final CommerceRuntime runtime;
    private final ManagedCart cart;
    private final PaymentProviderRouter payments;
    private final MerchantPspAccountService merchantAccounts;
    private final CommerceOrderRepository orders;
    private final CommercePayloads payloads;

    public CheckoutToolHandler(CommerceRuntime runtime, ManagedCart cart, PaymentProviderRouter payments,
                               MerchantPspAccountService merchantAccounts,
                               CommerceOrderRepository orders, CommercePayloads payloads) {
        this.runtime = runtime;
        this.cart = cart;
        this.payments = payments;
        this.merchantAccounts = merchantAccounts;
        this.orders = orders;
        this.payloads = payloads;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        CommerceConfig cfg = runtime.config(ctx);
        String currency = cfg.currency();
        if (currency == null || currency.isBlank()) {
            throw new ToolException("commerce currency is not configured for this profile");
        }

        Cart current = cart.view(ctx.sessionId(), currency);
        if (current.isEmpty()) {
            throw new ToolException("the cart is empty — add an item before checking out");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> fulfilment = input != null && input.get("fulfilment") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        // Per-profile PSP: the profile's commerce block may pin a provider; a blank
        // one falls back to the framework default. Resolve once so the audit row's
        // provider name matches the provider that actually opened the checkout.
        PaymentProvider provider = payments.resolve(cfg.paymentProvider());

        // Connected account precedence (design §6): explicit profile override →
        // the tenant's ACTIVE in-app onboarding record for this provider → null
        // (platform charge). So a merchant who clicked "Connect PayPal" gets funds
        // routed to their account with nothing pasted anywhere.
        String connectedAccountId = cfg.connectedAccountId();
        if (connectedAccountId == null || connectedAccountId.isBlank()) {
            connectedAccountId = merchantAccounts
                    .resolveConnectedAccount(ctx.principal().tenant(), provider.name())
                    .orElse(null);
        }

        CheckoutOutcome outcome = provider.createCheckout(new CheckoutRequest(
                ctx.principal().tenant(), ctx.sessionId(),
                ctx.principal().subject(), connectedAccountId,
                current, currency, fulfilment));

        String status = outcome instanceof CheckoutOutcome.OrderPlaced placed ? placed.status() : "PENDING";
        recordOrder(ctx, provider.name(), outcome, status);

        runtime.emit(ctx, CommercePayloads.CHECKOUT_RESULT, payloads.checkoutResult(outcome));

        // Terminal orders empty the cart now; hosted-page / intent flows keep the
        // cart until the payment is confirmed (a webhook clears it later).
        if (outcome instanceof CheckoutOutcome.OrderPlaced) {
            cart.clear(ctx.sessionId(), currency);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orderRef", outcome.orderRef());
        result.put("amountMinor", outcome.amountMinor());
        result.put("currency", outcome.currency());
        result.put("status", status);
        return result;
    }

    private void recordOrder(ExecutionContext ctx, String providerName, CheckoutOutcome outcome, String status) {
        UUID sessionUuid = parseUuid(ctx.sessionId().value());
        if (sessionUuid == null) return;   // non-UUID session (unit fixtures) — skip the durable row
        orders.save(new CommerceOrderEntity(
                UUID.randomUUID(), sessionUuid, ctx.principal().tenant().value(),
                providerName, outcome.orderRef(), outcome.amountMinor(), outcome.currency(), status));
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
