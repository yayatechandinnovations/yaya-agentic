package com.yayatechandinnovations.yayasales.fulfillment;

import com.yayatechandinnovations.yayasales.platform.Ids.CarrierId;
import com.yayatechandinnovations.yayasales.platform.Ids.DeliveryMethodId;
import com.yayatechandinnovations.yayasales.platform.money.Money;

import java.time.Instant;

/**
 * §8.4 — one way the order can arrive, priced and promised. The chosen
 * option's price joins the pricing quote's shippingTotal; the window is
 * snapshotted on the order so "arrives by Fri" is a commitment, not a guess.
 */
public record DeliveryOption(
        DeliveryMethodId method,
        DeliveryMethodKind kind,
        String label,
        Money price,
        Instant promisedFrom,
        Instant promisedTo,
        CarrierId carrier,
        String serviceLevel) {}
