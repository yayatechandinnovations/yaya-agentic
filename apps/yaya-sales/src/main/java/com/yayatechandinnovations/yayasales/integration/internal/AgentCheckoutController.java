package com.yayatechandinnovations.yayasales.integration.internal;

import com.yayatechandinnovations.yayasales.orders.CheckoutService;
import com.yayatechandinnovations.yayasales.orders.OrderService;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.platform.Ids.CartId;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * §10.2 — checkout + post-purchase order reads for the agent. Checkout is the
 * money-mover: the tool descriptor marks it {@code confirmable} (the engine's
 * two-phase confirm gates it) and the Idempotency-Key makes retries re-attach.
 * For {@code tender: COD} there is no PSP redirect — the order places
 * PENDING_PAYMENT and is collected at the door (§8.3).
 */
@RestController
@RequestMapping("/v1/sales/agent")
public class AgentCheckoutController {

    private final CheckoutService checkout;
    private final OrderService orders;

    public AgentCheckoutController(CheckoutService checkout, OrderService orders) {
        this.checkout = checkout;
        this.orders = orders;
    }

    @PostMapping("/checkout")
    public Map<String, Object> checkout(HttpServletRequest request,
                                        @RequestHeader(value = "Idempotency-Key", required = false)
                                        String idempotencyKey,
                                        @Valid @RequestBody Checkout body) {
        CheckoutService.CheckoutOutcome outcome = checkout.checkout(
                new CheckoutService.CheckoutCommand(
                        new CartId(body.cartHandle()), body.tender(),
                        Boolean.TRUE.equals(body.applyStoreCredit()),
                        body.returnUrl(), body.couponCode(), principal(),
                        idempotencyKey,
                        body.shipTo() == null ? null : body.shipTo().toAddress(),
                        body.deliveryMethodId() == null ? null
                                : new com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId(
                                        body.deliveryMethodId()),
                        body.slotId() == null ? null
                                : new com.yayatechandinnovations.yayasales.platform.Ids.SlotId(body.slotId())));

        return CheckoutResponses.framed(outcome);
    }

    @GetMapping("/orders/{id}")
    public Map<String, Object> order(HttpServletRequest request, @PathVariable String id) {
        OrderView order = orders.order(new OrderId(id))
                .filter(o -> o.store().equals(
                        ((com.yayatechandinnovations.yayasales.stores.StoreView)
                                request.getAttribute(AgentApiGuard.STORE_ATTRIBUTE)).id()))
                .orElseThrow(() -> new ApiErrors.NotFoundException("order", id));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.id().value());
        payload.put("status", order.status().name());
        payload.put("paymentState", order.paymentState().name());
        payload.put("currency", order.currency());
        payload.put("grandTotalMinor", order.totals().grand().amountMinor());
        payload.put("lines", order.lines().stream()
                .map(l -> Map.of("sku", l.sku().value(), "title", l.title(), "qty", l.qty()))
                .toList());
        return UiHints.framed(Map.of("order", payload), "order_status", payload);
    }

    /** The verified shopper subject when PRINCIPAL_TOKEN was forwarded (§11.3). */
    private static String principal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getSubject() : null;
    }

    public record Checkout(@NotBlank String cartHandle,
                           @NotNull TenderKind tender,
                           Boolean applyStoreCredit,
                           String returnUrl,
                           String couponCode,
                           AgentDeliveryController.AddressBody shipTo,
                           String deliveryMethodId,
                           String slotId) {}
}
