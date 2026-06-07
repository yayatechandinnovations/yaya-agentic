package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "profile_knowledge_bindings")
@IdClass(ProfileKnowledgeBindingEntity.PK.class)
public class ProfileKnowledgeBindingEntity {

    @Id @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Id @Column(name = "profile_id", length = 128)
    private String profileId;

    @Id @Column(name = "profile_version")
    private Integer profileVersion;

    @Id @Column(name = "source_id", length = 128)
    private String sourceId;

    @Id @Column(name = "source_version")
    private Integer sourceVersion;

    protected ProfileKnowledgeBindingEntity() {}

    public ProfileKnowledgeBindingEntity(String tenantId, String profileId, int profileVersion,
                                         String sourceId, int sourceVersion) {
        this.tenantId = tenantId;
        this.profileId = profileId;
        this.profileVersion = profileVersion;
        this.sourceId = sourceId;
        this.sourceVersion = sourceVersion;
    }

    public String getTenantId() { return tenantId; }
    public String getProfileId() { return profileId; }
    public Integer getProfileVersion() { return profileVersion; }
    public String getSourceId() { return sourceId; }
    public Integer getSourceVersion() { return sourceVersion; }

    public static class PK implements Serializable {
        private String tenantId; private String profileId; private Integer profileVersion;
        private String sourceId; private Integer sourceVersion;
        public PK() {}
        public PK(String tenantId, String profileId, Integer profileVersion,
                  String sourceId, Integer sourceVersion) {
            this.tenantId = tenantId; this.profileId = profileId;
            this.profileVersion = profileVersion; this.sourceId = sourceId;
            this.sourceVersion = sourceVersion;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(tenantId, pk.tenantId)
                    && Objects.equals(profileId, pk.profileId)
                    && Objects.equals(profileVersion, pk.profileVersion)
                    && Objects.equals(sourceId, pk.sourceId)
                    && Objects.equals(sourceVersion, pk.sourceVersion);
        }
        @Override public int hashCode() {
            return Objects.hash(tenantId, profileId, profileVersion, sourceId, sourceVersion);
        }
    }
}
