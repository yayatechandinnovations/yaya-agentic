package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "tools_registry")
@IdClass(ToolEntity.PK.class)
public class ToolEntity {

    @Id @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Id @Column(length = 128)
    private String id;

    @Id
    private Integer version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_schema_json", columnDefinition = "jsonb", nullable = false)
    private String inputSchemaJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_schema_json", columnDefinition = "jsonb", nullable = false)
    private String outputSchemaJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requires_json", columnDefinition = "jsonb", nullable = false)
    private String requiresJson = "{}";

    @Column(name = "handler_kind", nullable = false, length = 16)
    private String handlerKind;

    @Column(name = "handler_bean_name", length = 255)
    private String handlerBeanName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "handler_http_spec_json", columnDefinition = "jsonb")
    private String handlerHttpSpecJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_json", columnDefinition = "jsonb", nullable = false)
    private String policyJson = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "captures_json", columnDefinition = "jsonb", nullable = false)
    private String capturesJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "bindings_json", columnDefinition = "jsonb", nullable = false)
    private String bindingsJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suggestions_json", columnDefinition = "jsonb", nullable = false)
    private String suggestionsJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "table_outputs_json", columnDefinition = "jsonb")
    private String tableOutputsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "card_fields_json", columnDefinition = "jsonb")
    private String cardFieldsJson;

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected ToolEntity() {}

    public ToolEntity(String tenantId, String id, int version,
                      String inputSchemaJson, String outputSchemaJson,
                      String handlerKind) {
        this.tenantId = tenantId;
        this.id = id;
        this.version = version;
        this.inputSchemaJson = inputSchemaJson;
        this.outputSchemaJson = outputSchemaJson;
        this.handlerKind = handlerKind;
    }

    public String getTenantId() { return tenantId; }
    public String getId() { return id; }
    public Integer getVersion() { return version; }
    public String getInputSchemaJson() { return inputSchemaJson; }
    public String getOutputSchemaJson() { return outputSchemaJson; }
    public String getRequiresJson() { return requiresJson; }
    public String getHandlerKind() { return handlerKind; }
    public String getHandlerBeanName() { return handlerBeanName; }
    public String getHandlerHttpSpecJson() { return handlerHttpSpecJson; }
    public String getPolicyJson() { return policyJson; }
    public String getCapturesJson() { return capturesJson; }
    public String getBindingsJson() { return bindingsJson; }
    public String getSuggestionsJson() { return suggestionsJson; }
    public String getTableOutputsJson() { return tableOutputsJson; }
    public String getCardFieldsJson() { return cardFieldsJson; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setInputSchemaJson(String v) { this.inputSchemaJson = v; }
    public void setOutputSchemaJson(String v) { this.outputSchemaJson = v; }
    public void setRequiresJson(String v) { this.requiresJson = v; }
    public void setHandlerKind(String v) { this.handlerKind = v; }
    public void setHandlerBeanName(String v) { this.handlerBeanName = v; }
    public void setHandlerHttpSpecJson(String v) { this.handlerHttpSpecJson = v; }
    public void setPolicyJson(String v) { this.policyJson = v; }
    public void setCapturesJson(String v) { this.capturesJson = v == null || v.isBlank() ? "[]" : v; }
    public void setBindingsJson(String v) { this.bindingsJson = v == null || v.isBlank() ? "[]" : v; }
    public void setStatus(String v) { this.status = v; }

    public void setSuggestionsJson(String v) { this.suggestionsJson = v; }
    public void setTableOutputsJson(String v) { this.tableOutputsJson = v; }
    public void setCardFieldsJson(String v) { this.cardFieldsJson = v; }

    public static class PK implements Serializable {
        private String tenantId; private String id; private Integer version;
        public PK() {}
        public PK(String tenantId, String id, Integer version) {
            this.tenantId = tenantId; this.id = id; this.version = version;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(tenantId, pk.tenantId)
                    && Objects.equals(id, pk.id)
                    && Objects.equals(version, pk.version);
        }
        @Override public int hashCode() { return Objects.hash(tenantId, id, version); }
    }
}
