package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.yayatechandinnovations.yayasales.fulfillment.Carrier;
import org.springframework.stereotype.Component;

/**
 * §8.5/§8.9 v1 — the store's own fleet. No external label: the shipment id is
 * the tracking reference, and status advances come from the courier surface
 * (§10.4), not a webhook.
 */
@Component
public class InHouseCourierCarrier implements Carrier {

    public static final String KIND = "IN_HOUSE";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Label createLabel(ShipmentInput shipment) {
        return new Label(
                shipment.trackingNumber() == null ? shipment.shipment().value() : shipment.trackingNumber(),
                shipment.trackingUrl(), null);
    }
}
