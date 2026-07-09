package com.yayatechandinnovations.yayasales.platform.idempotency;

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
    public record Key(@Column(name = "store_id") String storeId,
                      @Column(name = "idem_key") String idemKey) implements Serializable {
        public Key {
            Objects.requireNonNull(storeId);
            Objects.requireNonNull(idemKey);
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_json")
    private String responseJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyEntity() {}

    public IdempotencyEntity(Key id, String requestHash, String responseJson, Instant createdAt) {
        this.id = id;
        this.requestHash = requestHash;
        this.responseJson = responseJson;
        this.createdAt = createdAt;
    }

    public String getRequestHash() { return requestHash; }
    public String getResponseJson() { return responseJson; }
}
