package com.yayatechandinnovations.yayasales.orders.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.cart.CartService;
import com.yayatechandinnovations.yayasales.cart.CartView;
import com.yayatechandinnovations.yayasales.inventory.InventoryLedger;
import com.yayatechandinnovations.yayasales.inventory.ReserveResult;
import com.yayatechandinnovations.yayasales.orders.CheckoutService;
import com.yayatechandinnovations.yayasales.orders.OrderView;
import com.yayatechandinnovations.yayasales.payments.StoreCreditService;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.payments.TenderOutcome;
import com.yayatechandinnovations.yayasales.payments.TenderService;
import com.yayatechandinnovations.yayasales.payments.TenderView;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.ReservationId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.idempotency.IdempotencyStore;
import com.yayatechandinnovations.yayasales.platform.money.Money;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import com.yayatechandinnovations.yayasales.pricing.PricedQuote;
import com.yayatechandinnovations.yayasales.stores.RegionView;
import com.yayatechandinnovations.yayasales.stores.SellingContextService;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §8.2 — the checkout saga. NOT one big transaction on purpose: the PSP call
 * must never run inside a DB transaction (§5.7). Steps run in their own
 * transactions with explicit compensations; the idempotency key makes a
 * retried call re-attach instead of re-reserving.
 *
 * <p>S3 scope note: address validation and delivery quoting/slots (§8.2 steps
 * 1/2a-b) join the saga in S5 with the fulfillment module — shipping is
 * quoted as zero until then.
 */
@Service
public class CheckoutSaga implements CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSaga.class);

    private final CartService carts;
    private final InventoryLedger ledger;
    private final TenderService tenders;
    private final StoreCreditService storeCredit;
    private final DefaultOrderService orders;
    private final StoreDirectory stores;
    private final SellingContextService sellingContext;
    private final IdempotencyStore idempotency;
    private final ObjectMapper json;

    public CheckoutSaga(CartService carts, InventoryLedger ledger, TenderService tenders,
                        StoreCreditService storeCredit, DefaultOrderService orders,
                        StoreDirectory stores, SellingContextService sellingContext,
                        IdempotencyStore idempotency, ObjectMapper json) {
        this.carts = carts;
        this.ledger = ledger;
        this.tenders = tenders;
        this.storeCredit = storeCredit;
        this.orders = orders;
        this.stores = stores;
        this.sellingContext = sellingContext;
        this.idempotency = idempotency;
        this.json = json;
    }

    @Override
    public CheckoutOutcome checkout(CheckoutCommand command) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("checkout requires an Idempotency-Key");
        }
        CartService.CheckoutHandoff handoff = carts.peek(command.cart());
        CartView cart = handoff.cart();
        StoreId store = cart.store();
        String requestHash = hash(command);

        // §5.5 — a retried checkout re-attaches to the same order.
        String idemKey = "checkout:" + command.idempotencyKey();
        var replay = idempotency.find(store, idemKey);
        if (replay.isPresent()) {
            if (!replay.get().matches(requestHash)) {
                throw new ApiErrors.ConflictException(
                        "Idempotency-Key reused with a different checkout request");
            }
            return reconstruct(replay.get().responseJson());
        }

        if (handoff.lines().isEmpty()) {
            throw new ApiErrors.UnprocessableException("cart is empty");
        }
        RegionView region = sellingContext.region(cart.region())
                .orElseThrow(() -> new IllegalStateException("cart region vanished"));
        if (!region.tenders().contains(command.tender())) {
            throw new ApiErrors.UnprocessableException(
                    "tender " + command.tender() + " is not offered in region " + region.name());
        }

        // Step 2 — revalidate every hold → FIRM with the checkout TTL. Fresh
        // holds stand; lapsed ones re-reserve against live stock or fail the line.
        Duration firmTtl = Duration.ofSeconds(
                stores.policiesOf(store).checkoutHoldTtlSeconds());
        List<DefaultOrderService.StoredLine> orderLines = new ArrayList<>();
        for (CartService.CheckoutHandoff.HandoffLine line : handoff.lines()) {
            List<String> firmed = new ArrayList<>();
            for (String reservationId : line.reservationIds()) {
                ReserveResult result = ledger.revalidate(new ReservationId(reservationId), firmTtl);
                if (result instanceof ReserveResult.Insufficient insufficient) {
                    // Holds firmed so far still belong to the cart; the sweeper
                    // or the next attempt handles them. Nothing was created.
                    return new CheckoutOutcome.Insufficient(line.sku(), insufficient.available());
                }
                firmed.add(((ReserveResult.Held) result).id().value());
            }
            orderLines.add(new DefaultOrderService.StoredLine(line.sku().value(), line.title(),
                    line.qty(), line.unitPriceMinor(), firmed));
        }

        // Step 2a — the authoritative quote (pricing; shipping joins in S5).
        PricedQuote quote = carts.quote(command.cart(), command.couponCode());

        // Step 3 — create Order(PENDING_PAYMENT), own tx.
        OrderView order = orders.createFromCheckout(store, cart.channel(), cart.region(),
                command.customerRef(), cart.currency(), orderLines, quote);

        // Step 4 — tenders. Compensation from here on: cancel the order (which
        // voids tenders, re-issues redeemed credit, and releases the holds).
        try {
            Money grand = quote.grandTotal();
            long creditApplied = 0;
            if (command.applyStoreCredit() && command.customerRef() != null) {
                long balance = storeCredit
                        .balance(store, command.customerRef(), grand.currency()).amountMinor();
                creditApplied = Math.min(balance, grand.amountMinor());
                if (creditApplied > 0) {
                    TenderView credit = tenders.create(store, order.id(), TenderKind.STORE_CREDIT,
                            new Money(creditApplied, grand.currency()));
                    tenders.begin(credit.id(), new TenderService.BeginContext(
                            command.returnUrl(), command.customerRef()));
                }
            }

            long remainder = grand.amountMinor() - creditApplied;
            CheckoutOutcome outcome;
            if (remainder > 0) {
                TenderView main = tenders.create(store, order.id(), command.tender(),
                        new Money(remainder, grand.currency()));
                TenderOutcome begun = tenders.begin(main.id(), new TenderService.BeginContext(
                        command.returnUrl(), command.customerRef()));
                outcome = switch (begun) {
                    case TenderOutcome.HostedUrl hosted ->
                            new CheckoutOutcome.HostedUrl(freshView(order.id()), hosted.url());
                    case TenderOutcome.PaymentIntent intent ->
                            new CheckoutOutcome.PaymentIntent(freshView(order.id()),
                                    intent.clientSecret(), intent.publishableKey());
                    case TenderOutcome.NothingDueNow nothing ->
                            new CheckoutOutcome.Placed(freshView(order.id()), nothing.instructions());
                    case TenderOutcome.SettledInstantly ignored ->
                            new CheckoutOutcome.Paid(freshView(order.id()));
                };
            } else {
                // Store credit covered everything; the settle event already flipped it PAID.
                outcome = new CheckoutOutcome.Paid(freshView(order.id()));
            }

            // Step 8 — the cart became an order: empty it WITHOUT releasing holds.
            carts.handOff(command.cart());
            idempotency.put(store, idemKey, requestHash, serialize(outcome));
            return outcome;

        } catch (RuntimeException e) {
            log.warn("checkout compensation for order {}: {}", order.id().value(), e.getMessage());
            try {
                orders.markAuthFailed(order.id(), e.getMessage());
                orders.cancel(order.id(), "checkout-compensation");
            } catch (RuntimeException comp) {
                log.error("checkout compensation itself failed for order {}: {}",
                        order.id().value(), comp.getMessage());
            }
            throw e;
        }
    }

    private OrderView freshView(OrderId id) {
        return orders.order(id).orElseThrow();
    }

    // ── idempotent replay plumbing ───────────────────────────────────────────

    private String serialize(CheckoutOutcome outcome) {
        Map<String, Object> stored = new LinkedHashMap<>();
        switch (outcome) {
            case CheckoutOutcome.HostedUrl h -> {
                stored.put("type", "HOSTED_URL");
                stored.put("orderId", h.order().id().value());
                stored.put("url", h.url());
            }
            case CheckoutOutcome.PaymentIntent pi -> {
                // Stored so a retried checkout replays the SAME intent — Stripe's
                // Idempotency-Key (the order id) made it the same intent anyway.
                stored.put("type", "PAYMENT_INTENT");
                stored.put("orderId", pi.order().id().value());
                stored.put("clientSecret", pi.clientSecret());
                stored.put("publishableKey", pi.publishableKey());
            }
            case CheckoutOutcome.Placed p -> {
                stored.put("type", "PLACED");
                stored.put("orderId", p.order().id().value());
                stored.put("instructions", p.instructions());
            }
            case CheckoutOutcome.Paid p -> {
                stored.put("type", "PAID");
                stored.put("orderId", p.order().id().value());
            }
            case CheckoutOutcome.Insufficient ignored ->
                    throw new IllegalStateException("Insufficient outcomes are not stored");
        }
        try {
            return json.writeValueAsString(stored);
        } catch (Exception e) {
            throw new IllegalStateException("unserializable checkout outcome", e);
        }
    }

    private CheckoutOutcome reconstruct(String storedJson) {
        try {
            Map<?, ?> stored = json.readValue(storedJson, Map.class);
            OrderView order = freshView(new OrderId(String.valueOf(stored.get("orderId"))));
            return switch (String.valueOf(stored.get("type"))) {
                case "HOSTED_URL" -> new CheckoutOutcome.HostedUrl(order,
                        String.valueOf(stored.get("url")));
                case "PAYMENT_INTENT" -> new CheckoutOutcome.PaymentIntent(order,
                        String.valueOf(stored.get("clientSecret")),
                        String.valueOf(stored.get("publishableKey")));
                case "PLACED" -> new CheckoutOutcome.Placed(order,
                        String.valueOf(stored.get("instructions")));
                case "PAID" -> new CheckoutOutcome.Paid(order);
                default -> throw new IllegalStateException("unknown stored outcome");
            };
        } catch (ApiErrors.NotFoundException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("corrupt stored checkout outcome", e);
        }
    }

    private static String hash(CheckoutCommand command) {
        String material = command.cart().value() + "|" + command.tender()
                + "|" + command.applyStoreCredit() + "|" + command.couponCode();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
