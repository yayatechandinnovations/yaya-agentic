package com.yayatechandinnovations.yayasales.orders;

import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderService {

    Optional<OrderView> order(OrderId id);

    List<OrderView> ordersOf(StoreId store, Optional<OrderStatus> status);

    /** Pre-payment only: voids pending tenders, releases the holds. */
    OrderView cancel(OrderId id, String actor);

    /**
     * Refund settled money (full when {@code amount} is null). Routes to the
     * original tender or to store credit (§8.3); moves the order to
     * PARTIALLY_REFUNDED / REFUNDED.
     */
    OrderView refund(OrderId id, Money amount, boolean toStoreCredit, String actor);

    List<Event> eventsOf(OrderId id);

    record Event(String type, String actor, String fromState, String toState, Instant at) {}
}
