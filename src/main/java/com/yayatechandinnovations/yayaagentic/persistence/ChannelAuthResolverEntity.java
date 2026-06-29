package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * One row of {@code channel_auth_resolvers} — a tenant-registered identity
 * resolver. {@code kind=BEAN} dispatches to a Spring {@code @Component} by
 * name; {@code kind=HTTP} POSTs to {@code tenants.host_base_url + http_path}.
 *
 * <p>The {@code service_secret_*} pair is AES-256-GCM ciphertext + IV via
 * {@code JwtSecretCipher} — the same envelope encryption used by
 * {@code tenant_jwt_secrets}. A DB dump alone does not leak the secret.
 *
 * <p>See {@code docs/design/channel-auth-resolver-design.md} §5 and §7.
 */
@Entity
@Table(name = "channel_auth_resolvers")
public class ChannelAuthResolverEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(name = "bean_name", length = 128)
    private String beanName;

    @Column(name = "http_path", length = 256)
    private String httpPath;

    @Column(name = "service_auth_kind", length = 32)
    private String serviceAuthKind;

    @Column(name = "service_secret_ciphertext")
    private String serviceSecretCiphertext;

    @Column(name = "service_secret_iv", length = 64)
    private String serviceSecretIv;

    // Public key identifier emitted in the outbound SIGNED_JWT JOSE header so a
    // tenant verifier that selects its HMAC key by `kid` can find it. Not a
    // secret — stored plaintext, safe to return on the GET surface.
    @Column(name = "service_jwt_kid", length = 128)
    private String serviceJwtKid;

    // Overrides the SIGNED_JWT `aud` claim; null = default to tenant id.
    @Column(name = "service_jwt_audience", length = 255)
    private String serviceJwtAudience;

    @Column(name = "timeout_ms", nullable = false)
    private int timeoutMs = 3000;

    @Column(name = "cache_enabled", nullable = false)
    private boolean cacheEnabled = true;

    @Column(name = "max_cache_seconds")
    private Integer maxCacheSeconds;

    @Column(name = "refresh_window_seconds", nullable = false)
    private int refreshWindowSeconds = 60;

    @Column(name = "max_token_age_seconds", nullable = false)
    private int maxTokenAgeSeconds = 3600;

    @Column(name = "on_refresh_failure", nullable = false, length = 32)
    private String onRefreshFailure = "REJECT";

    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    protected ChannelAuthResolverEntity() {}

    public ChannelAuthResolverEntity(String tenantId, String name, String description,
                                     String kind) {
        this.tenantId = tenantId;
        this.name = name;
        this.description = description;
        this.kind = kind;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getKind() { return kind; }
    public String getBeanName() { return beanName; }
    public String getHttpPath() { return httpPath; }
    public String getServiceAuthKind() { return serviceAuthKind; }
    public String getServiceSecretCiphertext() { return serviceSecretCiphertext; }
    public String getServiceSecretIv() { return serviceSecretIv; }
    public String getServiceJwtKid() { return serviceJwtKid; }
    public String getServiceJwtAudience() { return serviceJwtAudience; }
    public int getTimeoutMs() { return timeoutMs; }
    public boolean isCacheEnabled() { return cacheEnabled; }
    public Integer getMaxCacheSeconds() { return maxCacheSeconds; }
    public int getRefreshWindowSeconds() { return refreshWindowSeconds; }
    public int getMaxTokenAgeSeconds() { return maxTokenAgeSeconds; }
    public String getOnRefreshFailure() { return onRefreshFailure; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setDescription(String v) { this.description = v; }
    public void setBeanName(String v) { this.beanName = v; }
    public void setHttpPath(String v) { this.httpPath = v; }
    public void setServiceAuthKind(String v) { this.serviceAuthKind = v; }
    public void setServiceSecretCiphertext(String v) { this.serviceSecretCiphertext = v; }
    public void setServiceSecretIv(String v) { this.serviceSecretIv = v; }
    public void setServiceJwtKid(String v) { this.serviceJwtKid = v; }
    public void setServiceJwtAudience(String v) { this.serviceJwtAudience = v; }
    public void setTimeoutMs(int v) { this.timeoutMs = v; }
    public void setCacheEnabled(boolean v) { this.cacheEnabled = v; }
    public void setMaxCacheSeconds(Integer v) { this.maxCacheSeconds = v; }
    public void setRefreshWindowSeconds(int v) { this.refreshWindowSeconds = v; }
    public void setMaxTokenAgeSeconds(int v) { this.maxTokenAgeSeconds = v; }
    public void setOnRefreshFailure(String v) { this.onRefreshFailure = v; }
    public void setStatus(String v) { this.status = v; }
}
