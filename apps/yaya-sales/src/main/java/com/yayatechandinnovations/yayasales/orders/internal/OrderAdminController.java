package com.yayatechandinnovations.yayasales.orders.internal;

import com.yayatechandinnovations.yayasales.orders.OrderService;
import com.yayatechandinnovations.yayasales.orders.OrderStatus;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.payments.StoreCreditService;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.payments.TenderService;
import com.yayatechandinnovations.yayasales.payments.TenderView;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenderId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orders admin (design §10.1): list/detail with the event log + tenders,
 * cancel, add/settle tenders (the MANUAL confirm and the pre-courier COD
 * settle), refund (to PSP or store credit), and the store-credit balance ops.
 */
@RestController
@RequestMapping("/v1/sales/admin")
public class OrderAdminController {

    private static final String STORE_HEADER = "X-Yaya-Store-Id";

    private final OrderService orders;
    private final TenderService tenders;
    private final StoreCreditService storeCredit;

    public OrderAdminController(OrderService orders, TenderService tenders,
                                StoreCreditService storeCredit) {
        this.orders = orders;
        this.tenders = tenders;
        this.storeCredit = storeCredit;
    }

    @GetMapping("/orders")
    public List<OrderView> list(@RequestHeader(STORE_HEADER) String storeId,
                                @RequestParam(required = false) OrderStatus status) {
        return orders.ordersOf(new StoreId(storeId), Optional.ofNullable(status));
    }

    @GetMapping("/orders/{id}")
    public Map<String, Object> detail(@PathVariable String id) {
        OrderView order = orders.order(new OrderId(id))
                .orElseThrow(() -> new ApiErrors.NotFoundException("order", id));
        return Map.of(
                "order", order,
                "events", orders.eventsOf(order.id()),
                "tenders", tenders.tendersOf(order.id()));
    }

    @PostMapping("/orders/{id}/cancel")
    public OrderView cancel(@PathVariable String id, Authentication auth) {
        return orders.cancel(new OrderId(id), actor(auth));
    }

    @PostMapping("/orders/{id}/tenders")
    public TenderView addTender(@PathVariable String id,
                                @RequestHeader(STORE_HEADER) String storeId,
                                @Valid @RequestBody AddTender body) {
        OrderView order = orders.order(new OrderId(id))
                .orElseThrow(() -> new ApiErrors.NotFoundException("order", id));
        TenderView tender = tenders.create(new StoreId(storeId), order.id(), body.kind(),
                new Money(body.amountMinor(), order.currency()));
        tenders.begin(tender.id(), new TenderService.BeginContext(null, order.customerRef()));
        return tenders.tendersOf(order.id()).stream()
                .filter(t -> t.id().equals(tender.id())).findFirst().orElse(tender);
    }

    @PostMapping("/orders/{id}/tenders/{tenderId}/settle")
    public Map<String, String> settleTender(@PathVariable String id,
                                            @PathVariable String tenderId,
                                            Authentication auth) {
        tenders.settle(new TenderId(tenderId), actor(auth), null);
        return Map.of("status", "settled");
    }

    @PostMapping("/orders/{id}/refund")
    public OrderView refund(@PathVariable String id, @RequestBody Refund body,
                            Authentication auth) {
        OrderView order = orders.order(new OrderId(id))
                .orElseThrow(() -> new ApiErrors.NotFoundException("order", id));
        Money amount = body.amountMinor() == null
                ? null : new Money(body.amountMinor(), order.currency());
        return orders.refund(order.id(), amount, "store_credit".equals(body.to()), actor(auth));
    }

    // ── store credit (§8.3) ──────────────────────────────────────────────────

    @GetMapping("/store-credit/{customerRef}")
    public Map<String, Object> balance(@RequestHeader(STORE_HEADER) String storeId,
                                       @PathVariable String customerRef,
                                       @RequestParam String currency) {
        Money balance = storeCredit.balance(new StoreId(storeId), customerRef, currency);
        return Map.of("customerRef", customerRef,
                "balanceMinor", balance.amountMinor(), "currency", balance.currency());
    }

    @PostMapping("/store-credit")
    public Map<String, String> issue(@RequestHeader(STORE_HEADER) String storeId,
                                     @Valid @RequestBody IssueCredit body) {
        storeCredit.issue(new StoreId(storeId), body.customerRef(),
                new Money(body.amountMinor(), body.currency()),
                body.reason() == null ? "merchant issue" : body.reason(), null);
        return Map.of("status", "issued");
    }

    private static String actor(Authentication auth) {
        return auth == null ? "operator" : "op:" + auth.getName();
    }

    public record AddTender(@NotNull TenderKind kind, long amountMinor) {}

    public record Refund(Long amountMinor, String to) {}

    public record IssueCredit(@NotBlank String customerRef, long amountMinor,
                              @NotBlank String currency, String reason) {}
}
