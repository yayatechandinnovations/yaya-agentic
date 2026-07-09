package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.yayatechandinnovations.yayasales.fulfillment.DeliveryStatus;
import com.yayatechandinnovations.yayasales.fulfillment.Shipments;
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
@Table(name = "shipments")
public class ShipmentEntity {

    @Id
    private String id;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "location_id", nullable = false)
    private String locationId;

    @Column(name = "method_id")
    private String methodId;

    @Column(name = "method_kind", nullable = false)
    private String methodKind;

    @Column(name = "carrier_id")
    private String carrierId;

    @Column(name = "service_level")
    private String serviceLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lines_json", nullable = false)
    private String linesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parcel_json")
    private String parcelJson;

    @Column(nullable = false)
    private String status;

    @Column(name = "delivery_status")
    private String deliveryStatus;

    @Column(name = "tracking_number")
    private String trackingNumber;

    @Column(name = "tracking_url")
    private String trackingUrl;

    @Column(name = "label_ref")
    private String labelRef;

    @Column(name = "slot_id")
    private String slotId;

    @Column(name = "promised_from")
    private Instant promisedFrom;

    @Column(name = "promised_to")
    private Instant promisedTo;

    @Column(name = "pickup_otp")
    private String pickupOtp;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proof_json")
    private String proofJson;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ShipmentEntity() {}

    public ShipmentEntity(String id, String orderId, String storeId, String locationId,
                          String methodId, String methodKind, String carrierId,
                          String serviceLevel, String linesJson, String slotId,
                          Instant promisedFrom, Instant promisedTo) {
        this.id = id;
        this.orderId = orderId;
        this.storeId = storeId;
        this.locationId = locationId;
        this.methodId = methodId;
        this.methodKind = methodKind;
        this.carrierId = carrierId;
        this.serviceLevel = serviceLevel;
        this.linesJson = linesJson;
        this.slotId = slotId;
        this.promisedFrom = promisedFrom;
        this.promisedTo = promisedTo;
        this.status = Shipments.Status.DRAFT.name();
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getOrderId() { return orderId; }
    public String getStoreId() { return storeId; }
    public String getLocationId() { return locationId; }
    public String getMethodId() { return methodId; }
    public String getMethodKind() { return methodKind; }
    public String getCarrierId() { return carrierId; }
    public String getServiceLevel() { return serviceLevel; }
    public String getLinesJson() { return linesJson; }
    public String getParcelJson() { return parcelJson; }
    public String getStatus() { return status; }
    public String getDeliveryStatus() { return deliveryStatus; }
    public String getTrackingNumber() { return trackingNumber; }
    public String getTrackingUrl() { return trackingUrl; }
    public String getLabelRef() { return labelRef; }
    public String getSlotId() { return slotId; }
    public Instant getPromisedFrom() { return promisedFrom; }
    public Instant getPromisedTo() { return promisedTo; }
    public String getPickupOtp() { return pickupOtp; }
    public String getProofJson() { return proofJson; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getCreatedAt() { return createdAt; }

    public Shipments.Status status() {
        return Shipments.Status.valueOf(status);
    }

    /** The §8.6 pick→pack→dispatch FSM gate: out-of-order advances are conflicts. */
    public void transition(Shipments.Status to) {
        Shipments.Status from = status();
        boolean legal = switch (to) {
            case PICKING -> from == Shipments.Status.DRAFT;
            case PACKED -> from == Shipments.Status.PICKING;
            case DISPATCHED -> from == Shipments.Status.PACKED;
            case DRAFT -> false;
        };
        if (!legal) {
            throw new ApiErrors.ConflictException(
                    "shipment " + id + " cannot go " + from + " → " + to);
        }
        this.status = to.name();
    }

    public DeliveryStatus deliveryStatus() {
        return deliveryStatus == null ? null : DeliveryStatus.valueOf(deliveryStatus);
    }

    public void setDeliveryStatus(DeliveryStatus status) { this.deliveryStatus = status.name(); }
    public void setParcelJson(String parcelJson) { this.parcelJson = parcelJson; }
    public void setTracking(String number, String url) {
        this.trackingNumber = number;
        this.trackingUrl = url;
    }
    public void setLabelRef(String labelRef) { this.labelRef = labelRef; }
    public void setPickupOtp(String pickupOtp) { this.pickupOtp = pickupOtp; }
    public void setProofJson(String proofJson) { this.proofJson = proofJson; }
    public void setDispatchedAt(Instant at) { this.dispatchedAt = at; }
    public void setDeliveredAt(Instant at) { this.deliveredAt = at; }
}
