package com.yayatechandinnovations.yayaidentity.users;

import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    private String id;

    @Column(name = "primary_email")
    private String primaryEmail;

    private String phone;

    @Column(name = "display_name")
    private String displayName;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected UserEntity() {}

    public UserEntity(String id, String primaryEmail, String phone, String displayName,
                      String kind, String status, Instant createdAt) {
        this.id = id;
        this.primaryEmail = primaryEmail;
        this.phone = phone;
        this.displayName = displayName;
        this.kind = kind;
        this.status = status;
        this.createdAt = createdAt;
    }

    public User toDomain() {
        return new User(new UserId(id), primaryEmail, phone, displayName,
                User.Kind.valueOf(kind), User.Status.valueOf(status), createdAt);
    }

    public String getId() { return id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    /** Scrub PII on deletion (AC-44). The row and id survive so audit references stay valid. */
    public void scrubPii(Instant at) {
        this.primaryEmail = null;
        this.phone = null;
        this.displayName = null;
        this.status = User.Status.DELETED.name();
        this.deletedAt = at;
    }
}
