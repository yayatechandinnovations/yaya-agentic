package com.yayatechandinnovations.yayaidentity.signup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "pending_signups")
public class PendingSignupEntity {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "org_name", nullable = false)
    private String orgName;

    @Column(nullable = false)
    private String phone;

    @Column(nullable = false)
    private String status;

    @Column(name = "otp_sent_at")
    private Instant otpSentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected PendingSignupEntity() {}

    public PendingSignupEntity(String id, String userId, String orgName, String phone,
                               Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.orgName = orgName;
        this.phone = phone;
        this.status = "PENDING";
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getOrgName() { return orgName; }
    public String getPhone() { return phone; }
    public String getStatus() { return status; }
    public Instant getOtpSentAt() { return otpSentAt; }
    public Instant getExpiresAt() { return expiresAt; }

    public void setStatus(String status) { this.status = status; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public void markOtpSent(Instant at) { this.otpSentAt = at; }
}
