package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * Singleton (id=1) row backing the operator-auth strategy admin screen.
 * The four JSONB columns each carry a serialised layer from the design
 * (§5.1–§5.4). The delegate secret is stored encrypted via
 * {@code SecretCipher}.
 */
@Entity
@Table(name = "operator_auth_config")
public class OperatorAuthConfigEntity {

    public static final int SINGLETON_ID = 1;

    @Id
    @Column(nullable = false)
    private Integer id = SINGLETON_ID;

    @Column(name = "bootstrap_enabled", nullable = false)
    private boolean bootstrapEnabled;

    @Column(name = "bootstrap_username", nullable = false)
    private String bootstrapUsername;

    @Column(name = "bootstrap_password_hash", nullable = false)
    private String bootstrapPasswordHash;

    @Column(name = "http_delegate_enabled", nullable = false)
    private boolean httpDelegateEnabled;

    @Column(name = "http_delegate_url")
    private String httpDelegateUrl;

    @Column(name = "http_delegate_secret_enc")
    private byte[] httpDelegateSecretEnc;

    @Column(name = "http_delegate_timeout_ms", nullable = false)
    private int httpDelegateTimeoutMs;

    @Column(name = "http_delegate_require_https", nullable = false)
    private boolean httpDelegateRequireHttps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "http_delegate_request_json", columnDefinition = "jsonb")
    private String httpDelegateRequestJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "http_delegate_success_json", columnDefinition = "jsonb")
    private String httpDelegateSuccessJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "http_delegate_identity_json", columnDefinition = "jsonb")
    private String httpDelegateIdentityJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "http_delegate_failure_json", columnDefinition = "jsonb")
    private String httpDelegateFailureJson;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected OperatorAuthConfigEntity() {}

    public OperatorAuthConfigEntity(boolean bootstrapEnabled, String bootstrapUsername,
                                    String bootstrapPasswordHash, boolean httpDelegateEnabled,
                                    String httpDelegateUrl, byte[] httpDelegateSecretEnc,
                                    int httpDelegateTimeoutMs, boolean httpDelegateRequireHttps,
                                    String httpDelegateRequestJson, String httpDelegateSuccessJson,
                                    String httpDelegateIdentityJson, String httpDelegateFailureJson,
                                    OffsetDateTime updatedAt, String updatedBy) {
        this.id = SINGLETON_ID;
        this.bootstrapEnabled = bootstrapEnabled;
        this.bootstrapUsername = bootstrapUsername;
        this.bootstrapPasswordHash = bootstrapPasswordHash;
        this.httpDelegateEnabled = httpDelegateEnabled;
        this.httpDelegateUrl = httpDelegateUrl;
        this.httpDelegateSecretEnc = httpDelegateSecretEnc;
        this.httpDelegateTimeoutMs = httpDelegateTimeoutMs;
        this.httpDelegateRequireHttps = httpDelegateRequireHttps;
        this.httpDelegateRequestJson = httpDelegateRequestJson;
        this.httpDelegateSuccessJson = httpDelegateSuccessJson;
        this.httpDelegateIdentityJson = httpDelegateIdentityJson;
        this.httpDelegateFailureJson = httpDelegateFailureJson;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    public Integer getId() { return id; }
    public boolean isBootstrapEnabled() { return bootstrapEnabled; }
    public String getBootstrapUsername() { return bootstrapUsername; }
    public String getBootstrapPasswordHash() { return bootstrapPasswordHash; }
    public boolean isHttpDelegateEnabled() { return httpDelegateEnabled; }
    public String getHttpDelegateUrl() { return httpDelegateUrl; }
    public byte[] getHttpDelegateSecretEnc() { return httpDelegateSecretEnc; }
    public int getHttpDelegateTimeoutMs() { return httpDelegateTimeoutMs; }
    public boolean isHttpDelegateRequireHttps() { return httpDelegateRequireHttps; }
    public String getHttpDelegateRequestJson() { return httpDelegateRequestJson; }
    public String getHttpDelegateSuccessJson() { return httpDelegateSuccessJson; }
    public String getHttpDelegateIdentityJson() { return httpDelegateIdentityJson; }
    public String getHttpDelegateFailureJson() { return httpDelegateFailureJson; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }

    public void setBootstrapEnabled(boolean v) { this.bootstrapEnabled = v; }
    public void setBootstrapUsername(String v) { this.bootstrapUsername = v; }
    public void setBootstrapPasswordHash(String v) { this.bootstrapPasswordHash = v; }
    public void setHttpDelegateEnabled(boolean v) { this.httpDelegateEnabled = v; }
    public void setHttpDelegateUrl(String v) { this.httpDelegateUrl = v; }
    public void setHttpDelegateSecretEnc(byte[] v) { this.httpDelegateSecretEnc = v; }
    public void setHttpDelegateTimeoutMs(int v) { this.httpDelegateTimeoutMs = v; }
    public void setHttpDelegateRequireHttps(boolean v) { this.httpDelegateRequireHttps = v; }
    public void setHttpDelegateRequestJson(String v) { this.httpDelegateRequestJson = v; }
    public void setHttpDelegateSuccessJson(String v) { this.httpDelegateSuccessJson = v; }
    public void setHttpDelegateIdentityJson(String v) { this.httpDelegateIdentityJson = v; }
    public void setHttpDelegateFailureJson(String v) { this.httpDelegateFailureJson = v; }
    public void setUpdatedAt(OffsetDateTime v) { this.updatedAt = v; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
}
