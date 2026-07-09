package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.yayatechandinnovations.yayasales.fulfillment.Carrier;
import org.springframework.stereotype.Component;

/**
 * §8.5 v1 — no carrier API: the merchant enters the tracking number/URL by
 * hand at dispatch; tracking advances via the manual endpoint or the generic
 * carrier webhook if the merchant wires one.
 */
@Component
public class ManualCarrier implements Carrier {

    public static final String KIND = "MANUAL";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Label createLabel(ShipmentInput shipment) {
        return new Label(shipment.trackingNumber(), shipment.trackingUrl(), null);
    }
}
