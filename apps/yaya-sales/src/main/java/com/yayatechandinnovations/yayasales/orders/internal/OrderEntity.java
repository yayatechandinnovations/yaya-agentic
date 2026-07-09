package com.yayatechandinnovations.yayasales.orders.internal;

import com.yayatechandinnovations.yayasales.orders.OrderStatus;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "channel_id", nullable = false)
    private String channelId;

    @Column(name = "region_id", nullable = false)
    private String regionId;

    @Column(name = "customer_ref")
    private String customerRef;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private String source;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lines_json", nullable = false)
    private String linesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pricing_json", nullable = false)
    private String pricingJson;

    @Column(nullable = false)
    private String status;

    @Column(name = "payment_state", nullable = false)
    private String paymentState;

    @Column(name = "fulfillment_state", nullable = false)
    private String fulfillmentState;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OrderEntity() {}

    public OrderEntity(String id, String storeId, String channelId, String regionId,
                       String customerRef, String currency, String source,
                       String linesJson, String pricingJson) {
        this.id = id;
        this.storeId = storeId;
        this.channelId = channelId;
        this.regionId = regionId;
        this.customerRef = customerRef;
        this.currency = currency;
        this.source = source;
        this.linesJson = linesJson;
        this.pricingJson = pricingJson;
        this.status = OrderStatus.PENDING_PAYMENT.name();
        this.paymentState = "UNPAID";
        this.fulfillmentState = "UNFULFILLED";
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getChannelId() { return channelId; }
    public String getRegionId() { return regionId; }
    public String getCustomerRef() { return customerRef; }
    public String getCurrency() { return currency; }
    public String getSource() { return source; }
    public String getLinesJson() { return linesJson; }
    public String getPricingJson() { return pricingJson; }
    public String getStatus() { return status; }
    public String getPaymentState() { return paymentState; }
    public String getFulfillmentState() { return fulfillmentState; }
    public Instant getCreatedAt() { return createdAt; }

    public OrderStatus status() {
        return OrderStatus.valueOf(status);
    }

    /** The FSM gate (§8.1): illegal transitions are conflicts, not silent writes. */
    public void transition(OrderStatus to) {
        OrderStatus from = status();
        if (!from.canTransition(to)) {
            throw new ApiErrors.ConflictException(
                    "order " + id + " cannot go " + from + " → " + to);
        }
        this.status = to.name();
    }

    public void setPaymentState(String paymentState) { this.paymentState = paymentState; }
}
