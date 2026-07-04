package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/** One row of {@code role_members} (V67) — a verified subject's membership in a role. */
@Entity
@Table(name = "role_members")
@IdClass(RoleMemberEntity.PK.class)
public class RoleMemberEntity {

    @Id
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Id
    @Column(name = "role", length = 128)
    private String role;

    @Id
    @Column(name = "subject", length = 255)
    private String subject;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected RoleMemberEntity() {}

    public RoleMemberEntity(String tenantId, String role, String subject) {
        this.tenantId = tenantId;
        this.role = role;
        this.subject = subject;
    }

    public String getTenantId() { return tenantId; }
    public String getRole() { return role; }
    public String getSubject() { return subject; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public static class PK implements Serializable {
        private String tenantId;
        private String role;
        private String subject;

        public PK() {}
        public PK(String tenantId, String role, String subject) {
            this.tenantId = tenantId;
            this.role = role;
            this.subject = subject;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(tenantId, pk.tenantId)
                    && Objects.equals(role, pk.role)
                    && Objects.equals(subject, pk.subject);
        }
        @Override public int hashCode() { return Objects.hash(tenantId, role, subject); }
    }
}
