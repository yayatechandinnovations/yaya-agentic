package com.yayatechandinnovations.yayasales.integration.internal;

import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutOutcome;
import com.yayatechandinnovations.yayasales.orders.DraftOrderService;
import com.yayatechandinnovations.yayasales.orders.OrderService;
import com.yayatechandinnovations.yayasales.orders.OrderStatus;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.StoreView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §10.2/§8.1 — the conversational-selling primitive: the assistant assembles
 * an order (with merchant-approved one-off prices), then sends it — "here's
 * your order, tap to pay". Send is the money-mover: the tool descriptor marks
 * it {@code confirmable}, and its outcome rides the SAME {@code checkout}
 * ui-hint shapes as a cart checkout, so the chat renders one pay experience.
 */
@RestController
@RequestMapping("/v1/sales/agent/draft-orders")
public class AgentDraftOrderController {

    private final DraftOrderService drafts;
    private final OrderService orders;

    public AgentDraftOrderController(DraftOrderService drafts, OrderService orders) {
        this.drafts = drafts;
        this.orders = orders;
    }

    @PostMapping
    public Map<String, Object> create(HttpServletRequest request,
                                      @Valid @RequestBody CreateDraft body) {
        String customerRef = body.customerRef() != null ? body.customerRef() : principal();
        OrderView draft = drafts.create(store(request).id(),
                new DraftOrderService.DraftSpec(null, null, customerRef,
                        body.lines().stream()
                                .map(l -> new DraftOrderService.DraftLine(new VariantId(l.sku()),
                                        l.qty(), l.unitPriceMinor()))
                                .toList(),
                        body.note()),
                "agent");
        Map<String, Object> payload = draftPayload(draft);
        return UiHints.framed(Map.of("draftOrder", payload), "draft_order", payload);
    }

    @PostMapping("/{id}/send")
    public Map<String, Object> send(HttpServletRequest request, @PathVariable String id,
                                    @RequestHeader(value = "Idempotency-Key", required = false)
                                    String idempotencyKey,
                                    @RequestBody(required = false) Send body) {
        Send send = body == null ? new Send(null, null, null) : body;
        OrderView draft = scoped(request, id);
        CheckoutOutcome outcome = drafts.send(draft.id(),
                new DraftOrderService.SendCommand(send.tender(),
                        Boolean.TRUE.equals(send.applyStoreCredit()), send.returnUrl(),
                        principal(), idempotencyKey),
                "agent");
        return CheckoutResponses.framed(outcome);
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private static Map<String, Object> draftPayload(OrderView draft) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("draftOrderId", draft.id().value());
        payload.put("status", draft.status().name());
        payload.put("currency", draft.currency());
        payload.put("grandTotalMinor", draft.totals().grand().amountMinor());
        payload.put("lines", draft.lines().stream()
                .map(l -> Map.of("sku", l.sku().value(), "title", l.title(), "qty", l.qty(),
                        "unitPriceMinor", l.unitPrice().amountMinor()))
                .toList());
        return payload;
    }

    /** Another store's order — or a non-draft — is absent, not forbidden. */
    private OrderView scoped(HttpServletRequest request, String id) {
        StoreView store = store(request);
        return orders.order(new OrderId(id))
                .filter(o -> o.store().equals(store.id()))
                .filter(o -> o.status() == OrderStatus.DRAFT)
                .orElseThrow(() -> new ApiErrors.NotFoundException("draft order", id));
    }

    private static StoreView store(HttpServletRequest request) {
        return (StoreView) request.getAttribute(AgentApiGuard.STORE_ATTRIBUTE);
    }

    /** The verified shopper subject when PRINCIPAL_TOKEN was forwarded (§11.3). */
    private static String principal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getSubject() : null;
    }

    public record CreateDraft(@NotEmpty List<Line> lines, String customerRef, String note) {}

    public record Line(String sku, long qty, Long unitPriceMinor) {}

    public record Send(TenderKind tender, Boolean applyStoreCredit, String returnUrl) {}
}
