package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * A workspace allowed to use a template of a WhatsApp account
 * (whatsapp-shared-templates-design.md §3.1).
 */
@Entity
@Table(name = "whatsapp_template_holders")
@IdClass(WhatsAppTemplateHolderEntity.PK.class)
public class WhatsAppTemplateHolderEntity {

    @Id
    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Id
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** Operator or API key that shared it. */
    @Column(name = "granted_by", nullable = false, length = 128)
    private String grantedBy;

    @Column(name = "granted_at", nullable = false)
    private OffsetDateTime grantedAt;

    protected WhatsAppTemplateHolderEntity() {}

    public WhatsAppTemplateHolderEntity(Long templateId, String tenantId, String grantedBy,
                                        OffsetDateTime grantedAt) {
        this.templateId = templateId;
        this.tenantId = tenantId;
        this.grantedBy = grantedBy;
        this.grantedAt = grantedAt;
    }

    public Long getTemplateId() { return templateId; }
    public String getTenantId() { return tenantId; }
    public String getGrantedBy() { return grantedBy; }
    public OffsetDateTime getGrantedAt() { return grantedAt; }

    public static class PK implements Serializable {
        private Long templateId;
        private String tenantId;

        public PK() {}

        public PK(Long templateId, String tenantId) {
            this.templateId = templateId;
            this.tenantId = tenantId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK other)) return false;
            return Objects.equals(templateId, other.templateId) && Objects.equals(tenantId, other.tenantId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(templateId, tenantId);
        }
    }
}
