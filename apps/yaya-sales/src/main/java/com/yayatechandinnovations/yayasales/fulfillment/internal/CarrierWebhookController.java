package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryStatus;
import com.yayatechandinnovations.yayasales.fulfillment.Shipments;
import com.yayatechandinnovations.yayasales.platform.Ids.CarrierId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.idempotency.IdempotencyStore;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * §10.3 — carrier tracking webhook: signature-verified FIRST (HMAC-SHA256 of
 * the raw body with the carrier's configured secret), idempotent on the
 * carrier's event id, monotonic on delivery status. An unconfigured carrier
 * answers 503 and processes nothing — same posture as the PSP webhooks. v1's
 * MANUAL/IN_HOUSE carriers rarely wire one; the endpoint is the seam a real
 * carrier integration will land on (decision 8).
 */
@RestController
@RequestMapping("/v1/sales/webhooks")
public class CarrierWebhookController {

    private static final Logger log = LoggerFactory.getLogger(CarrierWebhookController.class);
    public static final String SIGNATURE_HEADER = "X-Carrier-Signature";

    private final CarrierRepository carriers;
    private final Shipments shipments;
    private final IdempotencyStore idempotency;
    private final ObjectMapper json;

    public CarrierWebhookController(CarrierRepository carriers, Shipments shipments,
                                    IdempotencyStore idempotency, ObjectMapper json) {
        this.carriers = carriers;
        this.shipments = shipments;
        this.idempotency = idempotency;
        this.json = json;
    }

    @PostMapping("/carrier/{carrierId}")
    public ResponseEntity<Map<String, String>> tracking(
            @PathVariable String carrierId,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody String rawBody) {
        CarrierEntity carrier = carriers.findById(carrierId).orElse(null);
        if (carrier == null) {
            return ResponseEntity.status(404).body(Map.of("error", "unknown carrier"));
        }
        String secret = webhookSecret(carrier);
        if (secret == null || secret.isBlank()) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "carrier webhook not configured"));
        }
        if (!verified(rawBody, signature, secret)) {
            log.warn("carrier webhook signature rejected for {}", carrierId);
            return ResponseEntity.status(403).body(Map.of("error", "bad signature"));
        }

        JsonNode node;
        try {
            node = json.readTree(rawBody == null ? "{}" : rawBody);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "malformed body"));
        }
        String eventId = node.path("eventId").asText(null);
        String trackingNumber = node.path("trackingNumber").asText(null);
        String statusRaw = node.path("status").asText(null);
        if (eventId == null || trackingNumber == null || statusRaw == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "eventId, trackingNumber and status are required"));
        }
        DeliveryStatus status;
        try {
            status = DeliveryStatus.valueOf(statusRaw);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "unknown status " + statusRaw));
        }

        Optional<Shipments.ShipmentView> shipment =
                shipments.byTracking(new CarrierId(carrierId), trackingNumber);
        if (shipment.isEmpty()) {
            return ok("unknown shipment");   // ack — stops carrier retries
        }

        // §14 idempotent carrier tracking: one TrackingEvent per carrier event id.
        StoreId store = shipment.get().store();
        String idemKey = "carrier-evt:" + carrierId + ":" + eventId;
        if (idempotency.find(store, idemKey).isPresent()) {
            return ok("duplicate");
        }
        idempotency.put(store, idemKey, carrierId, "{\"handled\":true}");

        Instant occurredAt = node.hasNonNull("occurredAt")
                ? Instant.parse(node.path("occurredAt").asText()) : Instant.now();
        try {
            shipments.advanceDelivery(shipment.get().id(), status, null,
                    node.path("description").asText(null), node.path("location").asText(null),
                    occurredAt, "carrier:" + carrierId);
        } catch (ApiErrors.ConflictException e) {
            return ok("stale");   // a late/backwards event never regresses the shipment
        }
        return ok("ok");
    }

    private String webhookSecret(CarrierEntity carrier) {
        if (carrier.getConfigJson() == null) {
            return null;
        }
        try {
            return json.readTree(carrier.getConfigJson()).path("webhookSecret").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean verified(String rawBody, String signature, String secret) {
        if (signature == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(
                    mac.doFinal((rawBody == null ? "" : rawBody).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private static ResponseEntity<Map<String, String>> ok(String status) {
        return ResponseEntity.ok(Map.of("status", status));
    }
}
