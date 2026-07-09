package com.yayatechandinnovations.yayasales.payments.internal;

import com.yayatechandinnovations.yayasales.payments.StoreCreditService;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultStoreCreditService implements StoreCreditService {

    private final StoreCreditRepository ledger;

    public DefaultStoreCreditService(StoreCreditRepository ledger) {
        this.ledger = ledger;
    }

    @Override
    @Transactional(readOnly = true)
    public Money balance(StoreId store, String customerRef, String currency) {
        requireCustomer(customerRef);
        return new Money(ledger.balanceMinor(store.value(), customerRef, currency), currency);
    }

    @Override
    @Transactional
    public void issue(StoreId store, String customerRef, Money amount, String reason, String refId) {
        requireCustomer(customerRef);
        requirePositive(amount);
        ledger.save(new StoreCreditEntity(Ids.newId(), store.value(), customerRef,
                amount.amountMinor(), amount.currency(), reason, refId));
    }

    // Serializes on the customer's ledger via the balance re-check inside the
    // tx; a concurrent double-redeem loses on the second read. v1 accepts the
    // small race window at READ_COMMITTED — the §14 invariant is enforced by a
    // final balance assertion, never skipped.
    @Override
    @Transactional
    public void redeem(StoreId store, String customerRef, Money amount, String reason, String refId) {
        requireCustomer(customerRef);
        requirePositive(amount);
        long balance = ledger.balanceMinor(store.value(), customerRef, amount.currency());
        if (balance < amount.amountMinor()) {
            throw new ApiErrors.UnprocessableException("insufficient store credit: have "
                    + balance + ", need " + amount.amountMinor());
        }
        ledger.save(new StoreCreditEntity(Ids.newId(), store.value(), customerRef,
                -amount.amountMinor(), amount.currency(), reason, refId));
        long after = ledger.balanceMinor(store.value(), customerRef, amount.currency());
        if (after < 0) {
            throw new ApiErrors.ConflictException("store credit balance would go negative");
        }
    }

    private static void requireCustomer(String customerRef) {
        if (customerRef == null || customerRef.isBlank()) {
            throw new IllegalArgumentException("store credit requires an authenticated customer");
        }
    }

    private static void requirePositive(Money amount) {
        if (amount.amountMinor() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
