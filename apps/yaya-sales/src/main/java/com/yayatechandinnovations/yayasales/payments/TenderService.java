package com.yayatechandinnovations.yayasales.payments;

import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenderId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

import java.util.List;

/**
 * The tender lifecycle (§8.3). Orders CREATES tenders during the checkout saga
 * and reacts to {@link TenderSettledEvent}s; this module moves the money.
 */
public interface TenderService {

    TenderView create(StoreId store, OrderId order, TenderKind kind, Money amount);

    /** Start collecting: PSP authorize → hosted URL; COD/MANUAL → nothing now; credit → settles. */
    TenderOutcome begin(TenderId tender, BeginContext ctx);

    /**
     * Idempotent settle (§14 single-settle): webhook capture, courier
     * collect-cod, or merchant confirm. A repeat is a no-op.
     */
    void settle(TenderId tender, String actor, String externalRef);

    /** Void an un-settled tender (saga compensation / cancel). Idempotent. */
    void voidTender(TenderId tender, String actor);

    /**
     * Refund settled money. PSP refunds through the provider; {@code toStoreCredit}
     * issues balance instead (the default exchange path, §8.3). Returns what was refunded.
     */
    Money refund(StoreId store, OrderId order, Money amount, boolean toStoreCredit,
                 String customerRef, String actor);

    List<TenderView> tendersOf(OrderId order);

    /** Sum of SETTLED tender amounts — the §14 split-tender balance check. */
    Money settledTotal(OrderId order, String currency);

    /** Sum of successful REFUND ledger rows — drives PARTIALLY_REFUNDED vs REFUNDED. */
    Money refundedTotal(OrderId order, String currency);

    record BeginContext(String returnUrl, String customerRef) {}
}
