package com.yayatechandinnovations.yayasales.orders.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "order_events")
public class OrderEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String actor;

    @Column(name = "from_state")
    private String fromState;

    @Column(name = "to_state")
    private String toState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_json")
    private String dataJson;

    @Column(nullable = false)
    private Instant at;

    protected OrderEventEntity() {}

    public OrderEventEntity(String orderId, String type, String actor,
                            String fromState, String toState, String dataJson) {
        this.orderId = orderId;
        this.type = type;
        this.actor = actor;
        this.fromState = fromState;
        this.toState = toState;
        this.dataJson = dataJson;
        this.at = Instant.now();
    }

    public String getType() { return type; }
    public String getActor() { return actor; }
    public String getFromState() { return fromState; }
    public String getToState() { return toState; }
    public Instant getAt() { return at; }
}
