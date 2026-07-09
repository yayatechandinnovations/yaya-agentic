package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.yayatechandinnovations.yayasales.fulfillment.DeliveryFleet.AgentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "delivery_agents")
public class DeliveryAgentEntity {

    @Id
    private String id;

    @Column(name = "store_id", nullable = false)
    private String storeId;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "legal_address_json", nullable = false)
    private String legalAddressJson;

    @Column(nullable = false)
    private String phone;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified;

    @Column(name = "phone_otp_hash")
    private String phoneOtpHash;

    @Column(name = "phone_otp_expires_at")
    private Instant phoneOtpExpiresAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "id_doc_json")
    private String idDocJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "vehicle_json", nullable = false)
    private String vehicleJson;

    @Column(nullable = false)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "serves_from_json")
    private String servesFromJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "coverage_json")
    private String coverageJson;

    // SHA-256 of the issued bearer token — never the token itself (§15).
    @Column(name = "credential_ref")
    private String credentialRef;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "reject_reason")
    private String rejectReason;

    protected DeliveryAgentEntity() {}

    public DeliveryAgentEntity(String id, String storeId, String legalName, String legalAddressJson,
                               String phone, String idDocJson, String vehicleJson,
                               String phoneOtpHash, Instant phoneOtpExpiresAt) {
        this.id = id;
        this.storeId = storeId;
        this.legalName = legalName;
        this.legalAddressJson = legalAddressJson;
        this.phone = phone;
        this.idDocJson = idDocJson;
        this.vehicleJson = vehicleJson;
        this.phoneOtpHash = phoneOtpHash;
        this.phoneOtpExpiresAt = phoneOtpExpiresAt;
        this.status = AgentStatus.PENDING.name();
        this.registeredAt = Instant.now();
    }

    public String getId() { return id; }
    public String getStoreId() { return storeId; }
    public String getLegalName() { return legalName; }
    public String getLegalAddressJson() { return legalAddressJson; }
    public String getPhone() { return phone; }
    public boolean isPhoneVerified() { return phoneVerified; }
    public String getPhoneOtpHash() { return phoneOtpHash; }
    public Instant getPhoneOtpExpiresAt() { return phoneOtpExpiresAt; }
    public String getIdDocJson() { return idDocJson; }
    public String getVehicleJson() { return vehicleJson; }
    public String getCredentialRef() { return credentialRef; }
    public Instant getRegisteredAt() { return registeredAt; }
    public Instant getApprovedAt() { return approvedAt; }
    public String getReviewedBy() { return reviewedBy; }
    public String getRejectReason() { return rejectReason; }

    public AgentStatus status() {
        return AgentStatus.valueOf(status);
    }

    public void setStatus(AgentStatus status) { this.status = status.name(); }

    public void markPhoneVerified() {
        this.phoneVerified = true;
        this.phoneOtpHash = null;
        this.phoneOtpExpiresAt = null;
    }

    public void setCredentialRef(String credentialRef) { this.credentialRef = credentialRef; }

    public void approve(String reviewedBy, Instant at) {
        setStatus(AgentStatus.APPROVED);
        this.reviewedBy = reviewedBy;
        this.approvedAt = at;
        this.rejectReason = null;
    }

    public void decide(AgentStatus status, String reviewedBy, String reason) {
        setStatus(status);
        this.reviewedBy = reviewedBy;
        this.rejectReason = reason;
    }
}
