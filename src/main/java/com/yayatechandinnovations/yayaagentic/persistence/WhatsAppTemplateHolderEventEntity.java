package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * Audit trail of template sharing: who let which workspace use a template, and
 * who took it away. Outlives the holder row and the template.
 */
@Entity
@Table(name = "whatsapp_template_holder_events")
public class WhatsAppTemplateHolderEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    @Column(name = "account_key", nullable = false, length = 64)
    private String accountKey;

    @Column(name = "template_name", nullable = false, length = 128)
    private String templateName;

    @Column(name = "locale", nullable = false, length = 16)
    private String locale;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** SHARED or UNSHARED. */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "actor", nullable = false, length = 128)
    private String actor;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    protected WhatsAppTemplateHolderEventEntity() {}

    public WhatsAppTemplateHolderEventEntity(Long templateId, String accountKey, String templateName,
                                             String locale, String tenantId, String action,
                                             String actor, OffsetDateTime occurredAt) {
        this.templateId = templateId;
        this.accountKey = accountKey;
        this.templateName = templateName;
        this.locale = locale;
        this.tenantId = tenantId;
        this.action = action;
        this.actor = actor;
        this.occurredAt = occurredAt;
    }

    public Long getId() { return id; }
    public Long getTemplateId() { return templateId; }
    public String getAccountKey() { return accountKey; }
    public String getTemplateName() { return templateName; }
    public String getLocale() { return locale; }
    public String getTenantId() { return tenantId; }
    public String getAction() { return action; }
    public String getActor() { return actor; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
}
