package com.yayatechandinnovations.yayasales.orders.internal;

import com.yayatechandinnovations.yayasales.orders.CheckoutService.CheckoutOutcome;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.payments.StoreCreditService;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.payments.TenderOutcome;
import com.yayatechandinnovations.yayasales.payments.TenderService;
import com.yayatechandinnovations.yayasales.payments.TenderView;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import org.springframework.stereotype.Component;

/**
 * §8.2 step 4 / §8.3, shared between the checkout saga and the draft-order
 * send: apply store credit first (split tender), begin the remainder's tender,
 * and map what payments yields onto the checkout outcome shapes. Compensation
 * is the CALLER's job — a thrown begin must be answered by the caller's
 * cancel path.
 */
@Component
class TenderStep {

    private final TenderService tenders;
    private final StoreCreditService storeCredit;
    private final DefaultOrderService orders;

    TenderStep(TenderService tenders, StoreCreditService storeCredit, DefaultOrderService orders) {
        this.tenders = tenders;
        this.storeCredit = storeCredit;
        this.orders = orders;
    }

    CheckoutOutcome collect(StoreId store, OrderId orderId, TenderKind tender, Money grand,
                            boolean applyStoreCredit, String returnUrl, String customerRef) {
        long creditApplied = 0;
        if (applyStoreCredit && customerRef != null) {
            long balance = storeCredit.balance(store, customerRef, grand.currency()).amountMinor();
            creditApplied = Math.min(balance, grand.amountMinor());
            if (creditApplied > 0) {
                TenderView credit = tenders.create(store, orderId, TenderKind.STORE_CREDIT,
                        new Money(creditApplied, grand.currency()));
                tenders.begin(credit.id(), new TenderService.BeginContext(returnUrl, customerRef));
            }
        }

        long remainder = grand.amountMinor() - creditApplied;
        if (remainder <= 0) {
            // Store credit covered everything; the settle event already flipped it PAID.
            return new CheckoutOutcome.Paid(fresh(orderId));
        }

        TenderView main = tenders.create(store, orderId, tender,
                new Money(remainder, grand.currency()));
        TenderOutcome begun = tenders.begin(main.id(),
                new TenderService.BeginContext(returnUrl, customerRef));
        return switch (begun) {
            case TenderOutcome.HostedUrl hosted ->
                    new CheckoutOutcome.HostedUrl(fresh(orderId), hosted.url());
            case TenderOutcome.PaymentIntent intent ->
                    new CheckoutOutcome.PaymentIntent(fresh(orderId),
                            intent.clientSecret(), intent.publishableKey());
            case TenderOutcome.NothingDueNow nothing ->
                    new CheckoutOutcome.Placed(fresh(orderId), nothing.instructions());
            case TenderOutcome.SettledInstantly ignored ->
                    new CheckoutOutcome.Paid(fresh(orderId));
        };
    }

    private OrderView fresh(OrderId id) {
        return orders.order(id).orElseThrow();
    }
}
