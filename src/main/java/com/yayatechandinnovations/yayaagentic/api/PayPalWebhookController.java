package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.commerce.payment.PayPalProperties;
import com.yayatechandinnovations.yayaagentic.commerce.payment.PaymentStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Public-internet edge for PayPal payment notifications. Verifies the
 * transmission signature via PayPal's verify-webhook-signature API when
 * configured (sandbox accepts unsigned for local dev), maps the event type to
 * our normalized status, and hands off to {@link PaymentStatusService} to flip
 * the order and clear the cart. Always acks 200 for a well-formed, verified
 * callback so PayPal stops retrying. The order ref is our own {@code custom_id},
 * echoed back on the capture. See
 * {@code commerce-redesign-authoring-rendering-paypal.md} Pillar C §C.1.
 */
@RestController
@RequestMapping("/v1/commerce/webhooks")
public class PayPalWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PayPalWebhookController.class);

    private final PayPalProperties props;
    private final PaymentStatusService paymentStatus;
    private final ObjectMapper json;
    private final RestClient http = RestClient.create();

    public PayPalWebhookController(PayPalProperties props, PaymentStatusService paymentStatus, ObjectMapper json) {
        this.props = props;
        this.paymentStatus = paymentStatus;
        this.json = json;
    }

    @PostMapping(value = "/paypal", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Void>> paypal(@RequestBody String rawBody, ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        return Mono.fromCallable(() -> handle(rawBody, headers))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<Void> handle(String rawBody, HttpHeaders headers) {
        if (!verified(rawBody, headers)) {
            log.warn("PayPal webhook signature rejected");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String orderRef;
        String eventType;
        try {
            JsonNode node = json.readTree(rawBody == null ? "{}" : rawBody);
            eventType = node.path("event_type").asText(null);
            orderRef = orderRefOf(node.path("resource"));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        if (orderRef == null || orderRef.isBlank() || eventType == null) {
            return ResponseEntity.badRequest().build();
        }
        paymentStatus.applyStatus(orderRef, normalize(eventType));
        return ResponseEntity.ok().build();   // ack even for unknown order — idempotent, stops retries
    }

    /** Our orderRef is the purchase unit's custom_id, present on both order and capture resources. */
    private static String orderRefOf(JsonNode resource) {
        String custom = resource.path("custom_id").asText(null);
        if (custom != null && !custom.isBlank()) return custom;
        JsonNode units = resource.path("purchase_units");
        if (units.isArray() && units.size() > 0) {
            String u = units.get(0).path("custom_id").asText(null);
            if (u != null && !u.isBlank()) return u;
        }
        return null;
    }

    private static String normalize(String eventType) {
        return switch (eventType.toUpperCase(Locale.ROOT)) {
            case "PAYMENT.CAPTURE.COMPLETED" -> "PAID";
            case "PAYMENT.CAPTURE.DENIED", "PAYMENT.CAPTURE.REVERSED",
                 "PAYMENT.CAPTURE.REFUNDED", "CHECKOUT.ORDER.VOIDED" -> "CANCELLED";
            default -> "PENDING";   // e.g. CHECKOUT.ORDER.APPROVED — awaiting capture
        };
    }

    /**
     * Sandbox (unconfigured, or no webhook id) accepts; a fully-wired PayPal
     * verifies the transmission via PayPal's verify-webhook-signature API.
     */
    private boolean verified(String rawBody, HttpHeaders headers) {
        if (!props.isConfigured() || props.webhookId() == null || props.webhookId().isBlank()) {
            return true;
        }
        try {
            String token = oauthToken();
            Map<String, Object> verify = new LinkedHashMap<>();
            verify.put("transmission_id", headers.getFirst("paypal-transmission-id"));
            verify.put("transmission_time", headers.getFirst("paypal-transmission-time"));
            verify.put("cert_url", headers.getFirst("paypal-cert-url"));
            verify.put("auth_algo", headers.getFirst("paypal-auth-algo"));
            verify.put("transmission_sig", headers.getFirst("paypal-transmission-sig"));
            verify.put("webhook_id", props.webhookId());
            verify.put("webhook_event", json.readTree(rawBody == null ? "{}" : rawBody));

            String response = http.post()
                    .uri(props.effectiveApiBase() + "/v1/notifications/verify-webhook-signature")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(verify))
                    .retrieve()
                    .body(String.class);
            JsonNode node = json.readTree(response == null ? "{}" : response);
            return "SUCCESS".equalsIgnoreCase(node.path("verification_status").asText());
        } catch (Exception e) {
            log.warn("PayPal webhook verification call failed", e);
            return false;
        }
    }

    private String oauthToken() throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (props.clientId() + ":" + props.clientSecret()).getBytes(StandardCharsets.UTF_8));
        String tokenResponse = http.post()
                .uri(props.effectiveApiBase() + "/v1/oauth2/token")
                .header("Authorization", "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials")
                .retrieve()
                .body(String.class);
        return json.readTree(tokenResponse == null ? "{}" : tokenResponse).path("access_token").asText("");
    }
}
