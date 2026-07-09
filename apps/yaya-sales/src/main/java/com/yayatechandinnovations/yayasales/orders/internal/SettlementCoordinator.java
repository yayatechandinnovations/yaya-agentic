package com.yayatechandinnovations.yayasales.orders.internal;

import com.yayatechandinnovations.yayasales.payments.TenderSettledEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The payments → orders reaction (§8.2 step 6→7): whenever ANY tender settles
 * (webhook capture, courier COD-collect, merchant confirm, instant store
 * credit), re-evaluate the order — PAID when the settled sum covers the grand
 * total. Synchronous and in the settle's transaction, so PAID + the SALE
 * movements + the outbox event commit atomically with the settlement.
 */
@Component
class SettlementCoordinator {

    private final DefaultOrderService orders;

    SettlementCoordinator(DefaultOrderService orders) {
        this.orders = orders;
    }

    @EventListener
    public void on(TenderSettledEvent event) {
        orders.onTenderSettled(event.order());
    }
}
