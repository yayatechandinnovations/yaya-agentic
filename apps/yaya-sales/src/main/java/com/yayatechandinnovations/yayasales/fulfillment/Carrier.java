package com.yayatechandinnovations.yayasales.fulfillment;

import com.yayatechandinnovations.yayasales.platform.Ids.ShipmentId;

/**
 * §8.5/§17 — the carrier seam. v1 ships {@code ManualCarrier} (merchant enters
 * tracking by hand) and {@code InHouseCourierCarrier} (own fleet, §8.9) — no
 * paid dependency in the critical path (decision 8). Live commercial-carrier
 * integrations (auto label / rate / tracking webhooks) slot in later behind
 * this same SPI.
 */
public interface Carrier {

    /** Matches {@code carriers.kind}: MANUAL | IN_HOUSE | <integration>. */
    String kind();

    /** Called at dispatch: what the shipment travels under. */
    Label createLabel(ShipmentInput shipment);

    record ShipmentInput(ShipmentId shipment, String trackingNumber, String trackingUrl) {}

    record Label(String trackingNumber, String trackingUrl, String labelRef) {}
}
