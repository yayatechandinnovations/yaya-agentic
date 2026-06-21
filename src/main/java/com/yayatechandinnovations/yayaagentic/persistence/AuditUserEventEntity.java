package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Audit row for identity / membership / workspace state-change events.
 * See {@code docs/specs/user-accounts/Backend-Data.md} §2.5 for the
 * canonical {@code kind} taxonomy.
 *
 * <p>{@code detailJson} stores only ids, hashes, and role names — never
 * raw PII (email, name, IP-when-sensitive). The dedicated columns carry
 * what little PII the audit retains.</p>
 */
@Entity
@Table(name = "audit_user_events")
public class AuditUserEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    private Long id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "workspace_id", length = 64)
    private String workspaceId;

    @Column(nullable = false)
    private String kind;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail_json", columnDefinition = "jsonb")
    private String detailJson;

    @Column(name = "client_ip")
    private String clientIp;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    protected AuditUserEventEntity() {}

    public AuditUserEventEntity(UUID userId, String workspaceId, String kind,
                                String detailJson, String clientIp, String userAgent,
                                OffsetDateTime occurredAt) {
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.kind = kind;
        this.detailJson = detailJson;
        this.clientIp = clientIp;
        this.userAgent = userAgent;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getKind() { return kind; }
    public String getDetailJson() { return detailJson; }
    public String getClientIp() { return clientIp; }
    public String getUserAgent() { return userAgent; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
