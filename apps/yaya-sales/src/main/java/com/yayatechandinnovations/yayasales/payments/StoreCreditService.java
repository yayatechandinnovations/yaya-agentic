package com.yayatechandinnovations.yayasales.payments;

import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

/**
 * §8.3 — per-customer merchant-issued balance: a tender AND the default refund
 * target. Balance = Σ ledger deltas; a redeem may never drive it negative (§14).
 */
public interface StoreCreditService {

    Money balance(StoreId store, String customerRef, String currency);

    void issue(StoreId store, String customerRef, Money amount, String reason, String refId);

    /** Throws {@code ApiErrors.UnprocessableException} when balance is insufficient. */
    void redeem(StoreId store, String customerRef, Money amount, String reason, String refId);
}
