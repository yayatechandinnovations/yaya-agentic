package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "auth_bindings")
@IdClass(AuthBindingEntity.PK.class)
public class AuthBindingEntity {

    @Id @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Id @Column(length = 128)
    private String id;

    @Column(name = "authenticator_ref", nullable = false, length = 128)
    private String authenticatorRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "authorizer_chain_json", columnDefinition = "jsonb", nullable = false)
    private String authorizerChainJson = "[]";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AuthBindingEntity() {}

    public AuthBindingEntity(String tenantId, String id, String authenticatorRef) {
        this.tenantId = tenantId;
        this.id = id;
        this.authenticatorRef = authenticatorRef;
    }

    public String getTenantId() { return tenantId; }
    public String getId() { return id; }
    public String getAuthenticatorRef() { return authenticatorRef; }
    public String getAuthorizerChainJson() { return authorizerChainJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setAuthenticatorRef(String v) { this.authenticatorRef = v; }
    public void setAuthorizerChainJson(String v) { this.authorizerChainJson = v; }

    public static class PK implements Serializable {
        private String tenantId; private String id;
        public PK() {}
        public PK(String tenantId, String id) { this.tenantId = tenantId; this.id = id; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(tenantId, pk.tenantId) && Objects.equals(id, pk.id);
        }
        @Override public int hashCode() { return Objects.hash(tenantId, id); }
    }
}
