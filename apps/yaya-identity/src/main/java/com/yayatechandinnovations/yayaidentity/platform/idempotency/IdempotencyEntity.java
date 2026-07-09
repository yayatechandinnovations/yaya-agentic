package com.yayatechandinnovations.yayaidentity.platform.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyEntity {

    @Embeddable
    public record Key(@Column(name = "scope") String scope,
                      @Column(name = "idem_key") String idemKey) implements Serializable {
        public Key {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(idemKey);
        }
    }

    @EmbeddedId
    private Key id;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_json")
    private String responseJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyEntity() {}

    public IdempotencyEntity(Key id, String responseJson, Instant createdAt) {
        this.id = id;
        this.responseJson = responseJson;
        this.createdAt = createdAt;
    }

    public String getResponseJson() { return responseJson; }
}
