package com.yayatechandinnovations.yayasales.payments.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.payments.internal.onboarding.MerchantAccounts;
import com.yayatechandinnovations.yayasales.payments.internal.providers.DLocalProperties;
import com.yayatechandinnovations.yayasales.payments.internal.providers.DLocalSignatures;
import com.yayatechandinnovations.yayasales.payments.internal.providers.PayPalProperties;
import com.yayatechandinnovations.yayasales.payments.internal.providers.StripeProperties;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.idempotency.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * §10.3 — the real-PSP webhooks (Stripe / PayPal / dLocal), ported from the
 * in-core controllers: signature-verified FIRST, idempotent on the provider's
 * event id, and the ONLY thing that flips a PSP order → PAID. The HTTP layer is
 * open (SecurityConfig permits {@code /v1/sales/webhooks/**}) because each
 * handler verifies its provider's signature itself. The sandbox provider's
 * webhook lives in {@link WebhookController}, untouched.
 *
 * <p>Unlike the legacy core (which accepted unsigned callbacks when no secret
 * was configured, for local dev), an unconfigured verifier here answers 503 and
 * processes nothing — an internet-facing settle endpoint never runs on trust.</p>
 */
@RestController
@RequestMapping("/v1/sales/webhooks")
public class PspWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PspWebhookController.class);
    /** Stripe's replay window for the signed timestamp. */
    private static final long STRIPE_TOLERANCE_SECONDS = 300;

    private final DefaultTenderService tenderService;
    private final MerchantAccounts merchantAccounts;
    private final TenderRepository tenders;
    private final IdempotencyStore idempotency;
    private final ObjectMapper json;
    private final StripeProperties stripe;
    private final PayPalProperties paypal;
    private final DLocalProperties dlocal;
    private final RestClient http = RestClient.create();

    public PspWebhookController(DefaultTenderService tenderService,
                                MerchantAccounts merchantAccounts,
                                TenderRepository tenders,
                                IdempotencyStore idempotency,
                                ObjectMapper json,
                                StripeProperties stripe,
                                PayPalProperties paypal,
                                DLocalProperties dlocal) {
        this.tenderService = tenderService;
        this.merchantAccounts = merchantAccounts;
        this.tenders = tenders;
        this.idempotency = idempotency;
        this.json = json;
        this.stripe = stripe;
        this.paypal = paypal;
        this.dlocal = dlocal;
    }

    // ── Stripe ───────────────────────────────────────────────────────────────

    @PostMapping("/stripe")
    public ResponseEntity<Map<String, String>> stripe(
            @RequestHeader(value = "Stripe-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        String secret = stripe.webhookSigningSecret();
        if (secret == null || secret.isBlank()) {
            return unconfigured("stripe");
        }
        if (!stripeVerified(rawBody, signature, secret)) {
            log.warn("Stripe webhook signature rejected");
            return ResponseEntity.status(403).body(Map.of("error", "bad signature"));
        }

        JsonNode node;
        try {
            node = json.readTree(rawBody == null ? "{}" : rawBody);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "malformed body"));
        }
        String eventId = text(node, "id");
        String type = text(node, "type");
        JsonNode object = node.path("data").path("object");

        if ("account.updated".equals(type)) {
            String accountId = object.path("id").asText(null);
            Optional<StoreId> store = merchantAccounts.storeByAccount("stripe", accountId);
            if (store.isEmpty()) {
                return ok("unknown account");   // idempotent — stops retries
            }
            if (duplicate(store.get(), "stripe", eventId)) {
                return ok("duplicate");
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("charges_enabled", object.path("charges_enabled").asBoolean(false));
            payload.put("details_submitted", object.path("details_submitted").asBoolean(false));
            merchantAccounts.reconcileWebhookByAccount("stripe", accountId, payload);
            return ok("ok");
        }

        if ("payment_intent.succeeded".equals(type)) {
            // Our order id rode out on metadata[order_id] and comes back here.
            String orderRef = object.path("metadata").path("order_id").asText(null);
            String pspRef = object.path("id").asText(null);
            return settle("stripe", eventId, orderRef, pspRef);
        }

        return ok("ignored");   // ack unrelated events; nothing to do
    }

    /** Verify Stripe's {@code t=…,v1=…} HMAC-SHA256 over {@code t.payload}, within the replay window. */
    private static boolean stripeVerified(String rawBody, String signatureHeader, String secret) {
        if (signatureHeader == null || rawBody == null) {
            return false;
        }
        try {
            String timestamp = null;
            String v1 = null;
            for (String part : signatureHeader.split(",")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                String k = part.substring(0, eq).trim();
                String v = part.substring(eq + 1).trim();
                if ("t".equals(k)) timestamp = v;
                else if ("v1".equals(k) && v1 == null) v1 = v;
            }
            if (timestamp == null || v1 == null) return false;
            if (Math.abs(System.currentTimeMillis() / 1000L - Long.parseLong(timestamp))
                    > STRIPE_TOLERANCE_SECONDS) {
                return false;   // replay window
            }
            String expected = hmacSha256Hex(secret, timestamp + "." + rawBody);
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8), v1.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    // ── PayPal ───────────────────────────────────────────────────────────────

    @PostMapping("/paypal")
    public ResponseEntity<Map<String, String>> paypal(@RequestHeader HttpHeaders headers,
                                                      @RequestBody String rawBody) {
        // The legacy core had no local PayPal verifier (verification is PayPal's
        // verify-webhook-signature API and needs a webhook id) — unconfigured is
        // 503 here instead of the legacy accept-unsigned.
        if (!paypal.isConfigured() || paypal.webhookId() == null || paypal.webhookId().isBlank()) {
            return unconfigured("paypal");
        }
        if (!paypalVerified(rawBody, headers)) {
            log.warn("PayPal webhook signature rejected");
            return ResponseEntity.status(403).body(Map.of("error", "bad signature"));
        }

        JsonNode node;
        try {
            node = json.readTree(rawBody == null ? "{}" : rawBody);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "malformed body"));
        }
        String eventId = text(node, "id");
        String eventType = text(node, "event_type");
        if (eventType == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "event_type required"));
        }
        JsonNode resource = node.path("resource");

        // Merchant-onboarding events (Partner Referrals) carry our tracking_id,
        // not an order custom_id — route them to the onboarding service instead.
        if (eventType.toUpperCase(Locale.ROOT).startsWith("MERCHANT.")) {
            String trackingRef = firstText(resource, "tracking_id", "partner_client_id");
            Optional<StoreId> store = merchantAccounts.storeByTrackingRef(trackingRef);
            if (store.isEmpty()) {
                return ok("unknown tracking ref");
            }
            if (duplicate(store.get(), "paypal", eventId)) {
                return ok("duplicate");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event_type", eventType);
            String merchantId = firstText(resource, "merchant_id", "merchantIdInPayPal");
            if (merchantId != null) payload.put("merchant_id", merchantId);
            merchantAccounts.reconcileWebhook(trackingRef, payload);
            return ok("ok");
        }

        if ("PAYMENT.CAPTURE.COMPLETED".equalsIgnoreCase(eventType)) {
            // Our order id rode out as the purchase unit's custom_id and comes back here.
            String orderRef = orderRefOf(resource);
            String pspRef = resource.path("id").asText(null);
            return settle("paypal", eventId, orderRef, pspRef);
        }

        return ok("ignored");   // e.g. CHECKOUT.ORDER.APPROVED — awaiting capture
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

    /** PayPal has no local verifier — the transmission is verified via PayPal's
     *  verify-webhook-signature API (ported verbatim from the legacy controller). */
    private boolean paypalVerified(String rawBody, HttpHeaders headers) {
        try {
            String token = paypalOauthToken();
            Map<String, Object> verify = new LinkedHashMap<>();
            verify.put("transmission_id", headers.getFirst("paypal-transmission-id"));
            verify.put("transmission_time", headers.getFirst("paypal-transmission-time"));
            verify.put("cert_url", headers.getFirst("paypal-cert-url"));
            verify.put("auth_algo", headers.getFirst("paypal-auth-algo"));
            verify.put("transmission_sig", headers.getFirst("paypal-transmission-sig"));
            verify.put("webhook_id", paypal.webhookId());
            verify.put("webhook_event", json.readTree(rawBody == null ? "{}" : rawBody));

            String response = http.post()
                    .uri(paypal.effectiveApiBase() + "/v1/notifications/verify-webhook-signature")
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

    private String paypalOauthToken() throws Exception {
        String basic = Base64.getEncoder().encodeToString(
                (paypal.clientId() + ":" + paypal.clientSecret()).getBytes(StandardCharsets.UTF_8));
        String tokenResponse = http.post()
                .uri(paypal.effectiveApiBase() + "/v1/oauth2/token")
                .header("Authorization", "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials")
                .retrieve()
                .body(String.class);
        return json.readTree(tokenResponse == null ? "{}" : tokenResponse)
                .path("access_token").asText("");
    }

    // ── dLocal ───────────────────────────────────────────────────────────────

    @PostMapping("/dlocal")
    public ResponseEntity<Map<String, String>> dlocal(
            @RequestHeader(value = "X-Date", required = false) String xDate,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody String rawBody) {
        if (!dlocal.isConfigured()) {
            return unconfigured("dlocal");
        }
        if (!dlocalVerified(rawBody, xDate, authorization)) {
            log.warn("dLocal webhook signature rejected");
            return ResponseEntity.status(403).body(Map.of("error", "bad signature"));
        }

        JsonNode node;
        try {
            node = json.readTree(rawBody == null ? "{}" : rawBody);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "malformed body"));
        }
        String orderRef = text(node, "order_id");
        String status = text(node, "status");
        if (orderRef == null || orderRef.isBlank() || status == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "order_id and status required"));
        }
        // dLocal notifications carry no distinct event id — dedupe on the payment
        // id (or the order+status transition, which is what a replay repeats).
        String paymentId = text(node, "id");
        String eventId = paymentId != null && !paymentId.isBlank()
                ? paymentId + ":" + status : orderRef + ":" + status;

        return switch (status.toUpperCase(Locale.ROOT)) {
            case "PAID", "AUTHORIZED" -> settle("dlocal", eventId, orderRef,
                    paymentId == null || paymentId.isBlank() ? orderRef : paymentId);
            // A rejected/expired payment leaves the tender PENDING — the shopper can
            // retry the hosted URL; cancellation flows through the order lifecycle.
            default -> ok("ignored");
        };
    }

    /** Live verifies the V2-HMAC-SHA256 signature over {@code xLogin + xDate + body}. */
    private boolean dlocalVerified(String rawBody, String xDate, String authorization) {
        if (authorization == null || xDate == null) return false;
        String expected = DLocalSignatures.hmacHex(
                dlocal.xLogin() + xDate + (rawBody == null ? "" : rawBody), dlocal.secretKey());
        String presented = authorization.contains("Signature:")
                ? authorization.substring(authorization.indexOf("Signature:") + "Signature:".length()).trim()
                : authorization.trim();
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    // ── shared plumbing ──────────────────────────────────────────────────────

    /** Capture-confirmed: dedupe on the provider event id, then settle the PSP
     *  tender for our echoed-back order id. Unknown order acks 200 — idempotent,
     *  stops retries (a replay after settle is a no-op inside settlePspByOrder). */
    private ResponseEntity<Map<String, String>> settle(String provider, String eventId,
                                                       String orderRef, String pspRef) {
        if (orderRef == null || orderRef.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "order reference required"));
        }
        Optional<StoreId> store = storeOfOrder(orderRef);
        if (store.isEmpty()) {
            log.warn("{} webhook for unknown order {}", provider, orderRef);
            return ok("unknown order");
        }
        if (duplicate(store.get(), provider, eventId)) {
            return ok("duplicate");
        }
        tenderService.settlePspByOrder(new OrderId(orderRef), pspRef);
        return ok("ok");
    }

    /** The store scoping the idempotency record — read off the order's tenders. */
    private Optional<StoreId> storeOfOrder(String orderId) {
        return tenders.findByOrderIdOrderByCreatedAtAsc(orderId).stream()
                .findFirst().map(t -> new StoreId(t.getStoreId()));
    }

    /** Idempotent on the provider's event id — a replay acknowledges without re-processing. */
    private boolean duplicate(StoreId store, String provider, String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return false;   // no event id to dedupe on; the settle itself is idempotent
        }
        String scopeKey = "webhook:" + provider + ":" + eventId;
        if (idempotency.find(store, scopeKey).isPresent()) {
            return true;
        }
        idempotency.put(store, scopeKey, provider, "{\"handled\":true}");
        return false;
    }

    private static ResponseEntity<Map<String, String>> unconfigured(String provider) {
        return ResponseEntity.status(503)
                .body(Map.of("error", provider + " webhook not configured"));
    }

    private static ResponseEntity<Map<String, String>> ok(String status) {
        return ResponseEntity.ok(Map.of("status", status));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = node.path(f).asText(null);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String hmacSha256Hex(String secret, String payload) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
