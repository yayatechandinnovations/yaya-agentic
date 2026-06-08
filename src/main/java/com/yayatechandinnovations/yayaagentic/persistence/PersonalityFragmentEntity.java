package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persistence row for {@code personality_fragments}. Read at runtime by a
 * future PG-backed {@code PersonalityProvider} (the current one is hardcoded);
 * written by the cross-tenant clone (§7.8).
 */
@Entity
@Table(name = "personality_fragments")
public class PersonalityFragmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 16)
    private String locale = "en";

    @Column(name = "voice_tone", nullable = false)
    private String voiceTone;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules_json", columnDefinition = "jsonb", nullable = false)
    private String rulesJson = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "refusals_json", columnDefinition = "jsonb", nullable = false)
    private String refusalsJson = "{}";

    @Column(nullable = false)
    private Integer version = 1;

    protected PersonalityFragmentEntity() {}

    public PersonalityFragmentEntity(String tenantId, String locale, String voiceTone, int version) {
        this.tenantId = tenantId;
        this.locale = locale;
        this.voiceTone = voiceTone;
        this.version = version;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getLocale() { return locale; }
    public String getVoiceTone() { return voiceTone; }
    public String getRulesJson() { return rulesJson; }
    public String getRefusalsJson() { return refusalsJson; }
    public Integer getVersion() { return version; }

    public void setVoiceTone(String v) { this.voiceTone = v; }
    public void setRulesJson(String v) { this.rulesJson = v; }
    public void setRefusalsJson(String v) { this.refusalsJson = v; }
}
