package com.yayatechandinnovations.yayasales.payments.internal;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.payments.PaymentProvider;
import com.yayatechandinnovations.yayasales.payments.StoreCreditService;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.payments.TenderOutcome;
import com.yayatechandinnovations.yayasales.payments.TenderService;
import com.yayatechandinnovations.yayasales.payments.TenderSettledEvent;
import com.yayatechandinnovations.yayasales.payments.TenderView;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenderId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §8.3 — one engine per tender kind, one ledger row per money movement, one
 * settle per tender ever (§14 single-settle). Settlement publishes
 * {@link TenderSettledEvent} in the same transaction; the orders module flips
 * the order PAID when the settled sum covers the grand total.
 */
@Service
public class DefaultTenderService implements TenderService {

    private final TenderRepository tenders;
    private final PaymentTransactionRepository transactions;
    private final StoreCreditService storeCredit;
    private final PaymentProviderRouter providerRouter;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;

    public DefaultTenderService(TenderRepository tenders,
                                PaymentTransactionRepository transactions,
                                StoreCreditService storeCredit,
                                PaymentProviderRouter providerRouter,
                                ApplicationEventPublisher events,
                                ObjectMapper json) {
        this.tenders = tenders;
        this.transactions = transactions;
        this.storeCredit = storeCredit;
        this.providerRouter = providerRouter;
        this.events = events;
        this.json = json;
    }

    @Override
    @Transactional
    public TenderView create(StoreId store, OrderId order, TenderKind kind, Money amount) {
        if (amount.amountMinor() <= 0) {
            throw new IllegalArgumentException("tender amount must be positive");
        }
        OrderTenderEntity tender = new OrderTenderEntity(Ids.newId(), order.value(),
                store.value(), kind.name(), amount.amountMinor(), amount.currency());
        tenders.save(tender);
        return toView(tender);
    }

    @Override
    @Transactional
    public TenderOutcome begin(TenderId tenderId, BeginContext ctx) {
        OrderTenderEntity tender = locked(tenderId);
        if (!tender.pending()) {
            throw new ApiErrors.ConflictException("tender " + tenderId.value()
                    + " is " + tender.getStatus() + " — cannot begin");
        }
        Money amount = new Money(tender.getAmountMinor(), tender.getCurrency());

        return switch (TenderKind.valueOf(tender.getKind())) {
            case PSP -> {
                PaymentProvider provider = providerRouter.resolve(new StoreId(tender.getStoreId()));
                PaymentProvider.AuthorizeOutcome outcome = provider.authorize(
                        new PaymentProvider.AuthorizeRequest(new StoreId(tender.getStoreId()),
                                new OrderId(tender.getOrderId()), amount, ctx.returnUrl()));
                yield switch (outcome) {
                    case PaymentProvider.AuthorizeOutcome.HostedUrl hosted -> {
                        record(tender, provider.name(), "AUTH", amount, hosted.pspRef(), "OK");
                        tender.setDetailJson(write(Map.of(
                                "provider", provider.name(), "pspRef", hosted.pspRef(),
                                "hostedUrl", hosted.url())));
                        yield new TenderOutcome.HostedUrl(hosted.url(), hosted.pspRef());
                    }
                    case PaymentProvider.AuthorizeOutcome.Intent intent -> {
                        record(tender, provider.name(), "AUTH", amount, intent.pspRef(), "OK");
                        // The clientSecret is a bearer credential for the payment sheet —
                        // handed to the shopper once, NEVER persisted on the tender.
                        tender.setDetailJson(write(Map.of(
                                "provider", provider.name(), "pspRef", intent.pspRef())));
                        yield new TenderOutcome.PaymentIntent(intent.clientSecret(),
                                intent.publishableKey(), intent.pspRef());
                    }
                    case PaymentProvider.AuthorizeOutcome.Captured captured -> {
                        record(tender, provider.name(), "CAPTURE", amount, captured.pspRef(), "OK");
                        tender.setDetailJson(write(Map.of(
                                "provider", provider.name(), "pspRef", captured.pspRef())));
                        settleLocked(tender, "psp:" + provider.name(), captured.pspRef());
                        yield new TenderOutcome.SettledInstantly();
                    }
                    case PaymentProvider.AuthorizeOutcome.Failed failed -> {
                        record(tender, provider.name(), "AUTH", amount, null, "FAILED");
                        throw new ApiErrors.UnprocessableException(
                                "payment authorization failed: " + failed.reason());
                    }
                };
            }
            case STORE_CREDIT -> {
                // Redeem and settle atomically — a failed redeem aborts the begin.
                storeCredit.redeem(new StoreId(tender.getStoreId()), ctx.customerRef(),
                        amount, "order tender", tender.getOrderId());
                tender.setDetailJson(write(Map.of("customerRef", ctx.customerRef())));
                settleLocked(tender, "store_credit", null);
                yield new TenderOutcome.SettledInstantly();
            }
            case COD -> {
                tender.setDetailJson(write(Map.of("collectOn", "delivery")));
                yield new TenderOutcome.NothingDueNow(
                        "Pay the courier in cash on delivery.");
            }
            case MANUAL -> {
                tender.setDetailJson(write(Map.of("awaiting", "merchant confirmation")));
                yield new TenderOutcome.NothingDueNow(
                        "Transfer the amount and share the reference; the merchant confirms.");
            }
        };
    }

    @Override
    @Transactional
    public void settle(TenderId tenderId, String actor, String externalRef) {
        OrderTenderEntity tender = locked(tenderId);
        if (tender.settled()) {
            return;   // §14 single-settle: a retried settle is a no-op
        }
        if (!tender.pending()) {
            throw new ApiErrors.ConflictException("tender " + tenderId.value()
                    + " is " + tender.getStatus() + " — cannot settle");
        }
        settleLocked(tender, actor, externalRef);
    }

    @Override
    @Transactional
    public void voidTender(TenderId tenderId, String actor) {
        OrderTenderEntity tender = locked(tenderId);
        if (!tender.pending()) {
            return;   // settled/refunded tenders are not voidable; repeat voids are no-ops
        }
        // A store-credit tender never sits PENDING (it settles at begin), so a
        // void never has to re-issue credit.
        record(tender, providerOf(tender), "VOID",
                new Money(tender.getAmountMinor(), tender.getCurrency()), null, "OK");
        tender.markVoided();
    }

    @Override
    @Transactional
    public Money refund(StoreId store, OrderId order, Money amount, boolean toStoreCredit,
                        String customerRef, String actor) {
        if (amount.amountMinor() <= 0) {
            throw new IllegalArgumentException("refund amount must be positive");
        }
        long settled = settledTotal(order, amount.currency()).amountMinor();
        long alreadyRefunded = transactions.findByOrderIdOrderByCreatedAtAsc(order.value()).stream()
                .filter(t -> "REFUND".equals(t.getKind()) && "OK".equals(t.getStatus()))
                .mapToLong(PaymentTransactionEntity::getAmountMinor).sum();
        if (amount.amountMinor() > settled - alreadyRefunded) {
            throw new ApiErrors.UnprocessableException("refund exceeds settled remainder: "
                    + (settled - alreadyRefunded));
        }

        if (toStoreCredit) {
            storeCredit.issue(store, customerRef, amount, "refund", order.value());
            OrderTenderEntity any = firstSettled(order);
            transactions.save(new PaymentTransactionEntity(Ids.newId(), order.value(),
                    any == null ? "-" : any.getId(), "store_credit", "REFUND",
                    amount.amountMinor(), amount.currency(), null, "OK"));
            return amount;
        }

        // Refund to the original tender: PSP goes through the provider; COD and
        // MANUAL are refunded out-of-band (cash back / counter-transfer) and the
        // ledger records that it happened (§8.3).
        OrderTenderEntity settledTender = firstSettled(order);
        if (settledTender == null) {
            throw new ApiErrors.UnprocessableException("order has no settled tender to refund");
        }
        if (TenderKind.valueOf(settledTender.getKind()) == TenderKind.PSP) {
            String pspRef = String.valueOf(readDetail(settledTender).get("pspRef"));
            PaymentProvider provider = providerRouter.resolve(store);
            PaymentProvider.RefundOutcome outcome = provider.refund(
                    new PaymentProvider.RefundRequest(store, order, amount, pspRef));
            if (outcome instanceof PaymentProvider.RefundOutcome.Failed failed) {
                transactions.save(new PaymentTransactionEntity(Ids.newId(), order.value(),
                        settledTender.getId(), provider.name(), "REFUND",
                        amount.amountMinor(), amount.currency(), pspRef, "FAILED"));
                throw new ApiErrors.UnprocessableException("refund failed: " + failed.reason());
            }
            String refundRef = ((PaymentProvider.RefundOutcome.Refunded) outcome).pspRef();
            transactions.save(new PaymentTransactionEntity(Ids.newId(), order.value(),
                    settledTender.getId(), provider.name(), "REFUND",
                    amount.amountMinor(), amount.currency(), refundRef, "OK"));
        } else {
            transactions.save(new PaymentTransactionEntity(Ids.newId(), order.value(),
                    settledTender.getId(), settledTender.getKind().toLowerCase(java.util.Locale.ROOT),
                    "REFUND", amount.amountMinor(), amount.currency(), null, "OK"));
        }
        if (amount.amountMinor() == settledTender.getAmountMinor()) {
            tenders.lockById(settledTender.getId()).ifPresent(OrderTenderEntity::markRefunded);
        }
        return amount;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenderView> tendersOf(OrderId order) {
        return tenders.findByOrderIdOrderByCreatedAtAsc(order.value())
                .stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Money settledTotal(OrderId order, String currency) {
        long sum = tenders.findByOrderIdOrderByCreatedAtAsc(order.value()).stream()
                .filter(t -> t.settled() || "REFUNDED".equals(t.getStatus()))
                .filter(t -> t.getCurrency().equals(currency))
                .mapToLong(OrderTenderEntity::getAmountMinor)
                .sum();
        return new Money(sum, currency);
    }

    @Override
    @Transactional(readOnly = true)
    public Money refundedTotal(OrderId order, String currency) {
        long sum = transactions.findByOrderIdOrderByCreatedAtAsc(order.value()).stream()
                .filter(t -> "REFUND".equals(t.getKind()) && "OK".equals(t.getStatus()))
                .filter(t -> t.getCurrency().equals(currency))
                .mapToLong(PaymentTransactionEntity::getAmountMinor)
                .sum();
        return new Money(sum, currency);
    }

    /** Webhook path: settle the PSP tender matching this order, dedup upstream. */
    @Transactional
    public void settlePspByOrder(OrderId order, String pspRef) {
        OrderTenderEntity tender = tenders.findFirstByOrderIdAndKindAndStatus(
                        order.value(), TenderKind.PSP.name(), "PENDING")
                .orElse(null);
        if (tender == null) {
            return;   // already settled (webhook replay) or no PSP tender — idempotent
        }
        OrderTenderEntity lockedTender = locked(new TenderId(tender.getId()));
        if (!lockedTender.pending()) {
            return;
        }
        record(lockedTender, providerOf(lockedTender), "CAPTURE",
                new Money(lockedTender.getAmountMinor(), lockedTender.getCurrency()), pspRef, "OK");
        settleLocked(lockedTender, "psp:webhook", pspRef);
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void settleLocked(OrderTenderEntity tender, String actor, String externalRef) {
        tender.markSettled(Instant.now());
        Map<String, Object> detail = new LinkedHashMap<>(readDetail(tender));
        detail.put("settledBy", actor);
        if (externalRef != null) detail.put("settleRef", externalRef);
        tender.setDetailJson(write(detail));
        events.publishEvent(new TenderSettledEvent(new StoreId(tender.getStoreId()),
                new OrderId(tender.getOrderId()), new TenderId(tender.getId()),
                TenderKind.valueOf(tender.getKind())));
    }

    private void record(OrderTenderEntity tender, String provider, String kind,
                        Money amount, String pspRef, String status) {
        transactions.save(new PaymentTransactionEntity(Ids.newId(), tender.getOrderId(),
                tender.getId(), provider, kind, amount.amountMinor(), amount.currency(),
                pspRef, status));
    }

    private OrderTenderEntity firstSettled(OrderId order) {
        return tenders.findByOrderIdOrderByCreatedAtAsc(order.value()).stream()
                .filter(t -> t.settled() || "REFUNDED".equals(t.getStatus()))
                .findFirst().orElse(null);
    }

    private OrderTenderEntity locked(TenderId id) {
        return tenders.lockById(id.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("tender", id.value()));
    }

    private String providerOf(OrderTenderEntity tender) {
        Object provider = readDetail(tender).get("provider");
        return provider == null
                ? tender.getKind().toLowerCase(java.util.Locale.ROOT) : String.valueOf(provider);
    }

    private TenderView toView(OrderTenderEntity t) {
        return new TenderView(new TenderId(t.getId()), new OrderId(t.getOrderId()),
                TenderKind.valueOf(t.getKind()),
                new Money(t.getAmountMinor(), t.getCurrency()),
                TenderView.Status.valueOf(t.getStatus()),
                readDetail(t), t.getCreatedAt(), t.getSettledAt());
    }

    private Map<String, Object> readDetail(OrderTenderEntity t) {
        if (t.getDetailJson() == null) return Map.of();
        try {
            return json.readValue(t.getDetailJson(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("corrupt tender detail_json", e);
        }
    }

    private String write(Map<String, Object> detail) {
        try {
            return json.writeValueAsString(detail);
        } catch (Exception e) {
            throw new IllegalStateException("unserializable tender detail", e);
        }
    }

    /** The ledger rows for an order — surfaced by the admin order detail. */
    @Transactional(readOnly = true)
    public List<PaymentTransactionEntity> transactionsOf(OrderId order) {
        return transactions.findByOrderIdOrderByCreatedAtAsc(order.value());
    }
}
