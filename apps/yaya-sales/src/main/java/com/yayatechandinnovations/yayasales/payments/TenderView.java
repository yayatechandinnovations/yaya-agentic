package com.yayatechandinnovations.yayasales.payments;

import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.TenderId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

import java.time.Instant;
import java.util.Map;

public record TenderView(
        TenderId id,
        OrderId order,
        TenderKind kind,
        Money amount,
        Status status,
        Map<String, Object> detail,
        Instant createdAt,
        Instant settledAt) {

    public enum Status { PENDING, SETTLED, REFUNDED, VOIDED }
}
