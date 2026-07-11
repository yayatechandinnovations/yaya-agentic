package com.yayatechandinnovations.yayasales.platform;

import java.util.UUID;

/**
 * Typed identifier records — the same discipline as yaya-agentic's
 * {@code core/Ids} and yaya-identity's {@code platform/Ids}: raw {@code String}
 * IDs never cross module APIs, so a {@link VariantId} can't be wired where a
 * {@link LocationId} belongs (parent design §5, module design §2).
 *
 * <p>{@link StoreId} is distinct from the yaya-agentic tenant id on purpose —
 * a tenant owns 1..N stores (decision 7). {@link TenantRef} carries the
 * agent-side tenant only where the integration seam needs it (service tokens,
 * store lookup); it is a reference, not a foreign key (decision 5).
 */
public final class Ids {

    private Ids() {}

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    // stores & selling context (§6.1)
    public record StoreId(String value) {}
    public record TenantRef(String value) {}
    public record LocationId(String value) {}
    public record ChannelId(String value) {}
    public record RegionId(String value) {}

    // catalog
    public record CatalogId(String value) {}
    public record ProductId(String value) {}
    public record VariantId(String value) {}

    // pricing
    public record PriceListId(String value) {}
    public record PromotionId(String value) {}

    // inventory (§7)
    public record MovementId(String value) {}
    public record ReservationId(String value) {}

    // cart & orders (§8.1–8.2)
    public record CartId(String value) {}
    public record OrderId(String value) {}
    public record TenderId(String value) {}
    public record PaymentTransactionId(String value) {}

    // fulfillment & delivery (§8.4–8.9)
    public record ZoneId(String value) {}
    public record RateId(String value) {}
    public record DeliveryMethodId(String value) {}
    public record CarrierId(String value) {}
    public record SlotId(String value) {}
    public record ShipmentId(String value) {}
    public record TrackingEventId(String value) {}
    public record AgentId(String value) {}          // delivery agent (courier)
    public record AssignmentId(String value) {}

    // returns & purchasing (§8.8, §9)
    public record RmaId(String value) {}
    public record SupplierId(String value) {}
    public record PurchaseOrderId(String value) {}
    public record GoodsReceiptId(String value) {}

    // customers
    public record CustomerId(String value) {}
    public record AddressId(String value) {}

    // media (uploaded images)
    public record MediaId(String value) {}
}
