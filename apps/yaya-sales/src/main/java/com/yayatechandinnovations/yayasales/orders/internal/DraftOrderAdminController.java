package com.yayatechandinnovations.yayasales.orders.internal;

import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutOutcome;
import com.yayatechandinnovations.yayasales.orders.DraftOrderService;
import com.yayatechandinnovations.yayasales.orders.OrderService;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.stores.ChannelView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draft-order admin (design §10.1): the merchant builds a draft (one-off
 * prices allowed), edits it while DRAFT, and sends it for payment — the "here
 * is your order, tap to pay" flow driven from the console. Listing rides the
 * existing orders endpoints ({@code ?status=DRAFT}); cancel is the plain order
 * cancel.
 */
@RestController
@RequestMapping("/v1/sales/admin/draft-orders")
public class DraftOrderAdminController {

    private static final String STORE_HEADER = "X-Yaya-Store-Id";

    private final DraftOrderService drafts;
    private final OrderService orders;

    public DraftOrderAdminController(DraftOrderService drafts, OrderService orders) {
        this.drafts = drafts;
        this.orders = orders;
    }

    @PostMapping
    public OrderView create(@RequestHeader(STORE_HEADER) String storeId,
                            @Valid @RequestBody CreateDraft body, Authentication auth) {
        return drafts.create(new StoreId(storeId),
                new DraftOrderService.DraftSpec(body.channel(),
                        body.regionId() == null ? null : new RegionId(body.regionId()),
                        body.customerRef(), toLines(body.lines()), body.note()),
                actor(auth));
    }

    @PutMapping("/{id}")
    public OrderView update(@RequestHeader(STORE_HEADER) String storeId,
                            @PathVariable String id,
                            @Valid @RequestBody UpdateDraft body, Authentication auth) {
        return drafts.update(scoped(storeId, id), toLines(body.lines()), actor(auth));
    }

    @PostMapping("/{id}/send")
    public Map<String, Object> send(@RequestHeader(STORE_HEADER) String storeId,
                                    @PathVariable String id,
                                    @RequestHeader(value = "Idempotency-Key", required = false)
                                    String idempotencyKey,
                                    @RequestBody(required = false) Send body, Authentication auth) {
        Send send = body == null ? new Send(null, null, null, null) : body;
        CheckoutOutcome outcome = drafts.send(scoped(storeId, id),
                new DraftOrderService.SendCommand(send.tender(),
                        Boolean.TRUE.equals(send.applyStoreCredit()), send.returnUrl(),
                        send.customerRef(), idempotencyKey),
                actor(auth));
        return describe(outcome);
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    /** An order of another store is answered as absent — existence is not leaked. */
    private OrderId scoped(String storeId, String id) {
        return orders.order(new OrderId(id))
                .filter(o -> o.store().value().equals(storeId))
                .orElseThrow(() -> new ApiErrors.NotFoundException("order", id))
                .id();
    }

    private static List<DraftOrderService.DraftLine> toLines(List<Line> lines) {
        return lines.stream()
                .map(l -> new DraftOrderService.DraftLine(new VariantId(l.sku()), l.qty(),
                        l.unitPriceMinor()))
                .toList();
    }

    private static Map<String, Object> describe(CheckoutOutcome outcome) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (outcome) {
            case CheckoutOutcome.HostedUrl hosted -> {
                out.put("status", "AWAITING_PAYMENT");
                out.put("orderId", hosted.order().id().value());
                out.put("paymentUrl", hosted.url());
            }
            case CheckoutOutcome.PaymentIntent intent -> {
                out.put("status", "AWAITING_PAYMENT");
                out.put("orderId", intent.order().id().value());
                out.put("clientSecret", intent.clientSecret());
                out.put("publishableKey", intent.publishableKey());
            }
            case CheckoutOutcome.Placed placed -> {
                out.put("status", "PLACED");
                out.put("orderId", placed.order().id().value());
                out.put("instructions", placed.instructions());
            }
            case CheckoutOutcome.Paid paid -> {
                out.put("status", "PAID");
                out.put("orderId", paid.order().id().value());
            }
            case CheckoutOutcome.Insufficient insufficient -> {
                out.put("status", "INSUFFICIENT_STOCK");
                out.put("sku", insufficient.sku().value());
                out.put("available", insufficient.available());
            }
        }
        return out;
    }

    private static String actor(Authentication auth) {
        return auth == null ? "operator" : "op:" + auth.getName();
    }

    public record CreateDraft(ChannelView.Kind channel, String regionId, String customerRef,
                              String note, @NotEmpty List<Line> lines) {}

    public record UpdateDraft(@NotEmpty List<Line> lines) {}

    public record Line(String sku, long qty, Long unitPriceMinor) {}

    public record Send(TenderKind tender, Boolean applyStoreCredit, String returnUrl,
                       String customerRef) {}
}
