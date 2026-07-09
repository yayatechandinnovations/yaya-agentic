package com.yayatechandinnovations.yayasales.orders;

import com.yayatechandinnovations.yayasales.platform.Ids.ChannelId;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.RegionId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.Ids.VariantId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

import java.time.Instant;
import java.util.List;

public record OrderView(
        OrderId id,
        StoreId store,
        ChannelId channel,
        RegionId region,
        String customerRef,
        String currency,
        Source source,
        OrderStatus status,
        PaymentState paymentState,
        String fulfillmentState,
        List<Line> lines,
        Totals totals,
        Instant createdAt) {

    public enum Source { CHECKOUT, DRAFT }

    public enum PaymentState { UNPAID, PARTIALLY_SETTLED, PAID, PARTIALLY_REFUNDED, REFUNDED }

    public record Line(VariantId sku, String title, long qty, Money unitPrice,
                       List<String> reservationIds) {}

    /** The §6.2 quote snapshot — immutable once placed; refunds reconstruct from it. */
    public record Totals(Money subtotal, Money discount, Money tax, boolean taxInclusive,
                         Money shipping, Money grand) {}
}
