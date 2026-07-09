package com.yayatechandinnovations.yayaidentity.organizations;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "invitations")
public class InvitationEntity {

    @Id
    private String id;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    private String email;
    private String phone;
    private String role;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "invited_by")
    private String invitedBy;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    protected InvitationEntity() {}

    public InvitationEntity(String id, String orgId, String email, String role, String tokenHash,
                            String invitedBy, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.orgId = orgId;
        this.email = email;
        this.role = role;
        this.tokenHash = tokenHash;
        this.invitedBy = invitedBy;
        this.status = Invitation.Status.PENDING.name();
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Invitation toDomain() {
        return new Invitation(id, new OrgId(orgId), email, role,
                Invitation.Status.valueOf(status), expiresAt, createdAt);
    }

    public String getOrgId() { return orgId; }
    public String getEmail() { return email; }
    public String getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }

    public void setStatus(String status) { this.status = status; }
    public void markAccepted(Instant at) { this.status = Invitation.Status.ACCEPTED.name(); this.acceptedAt = at; }
}
