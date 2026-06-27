package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@code api_keys}. Workspace-scoped reads carry a
 * {@code workspaceId} parameter; the three deliberately cross-workspace methods
 * (the bearer-token lookup, the global secret-hash uniqueness check, and the
 * by-PK last-used touch) are marked {@link WorkspaceUnscoped} — the verifier
 * discovers the workspace FROM the key, so the lookup cannot be scoped first.
 */
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, UUID> {

    /**
     * Active (non-revoked) keys sharing a prefix. Normally one row; a prefix
     * collision (silently retried on create) could briefly yield more, so the
     * verifier disambiguates by hash. Revoked rows are excluded so a revoked
     * key can never re-authenticate even if its cache entry lingers.
     *
     * <p>Inherently cross-workspace: a bearer token carries no workspace; the
     * workspace is read off the matched key. This IS the token-bearer-lookup
     * case the scan test blesses.</p>
     */
    @WorkspaceUnscoped("bearer-token lookup — workspace is discovered from the matched key")
    List<ApiKeyEntity> findByKeyPrefixAndRevokedAtIsNull(String keyPrefix);

    List<ApiKeyEntity> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);

    Optional<ApiKeyEntity> findByIdAndWorkspaceId(UUID id, String workspaceId);

    @WorkspaceUnscoped("global secret-hash uniqueness at mint — spans all workspaces by design")
    boolean existsBySecretHash(byte[] secretHash);

    long countByWorkspaceIdAndRevokedAtIsNull(String workspaceId);

    List<ApiKeyEntity> findByCreatedByUserIdAndWorkspaceIdAndRevokedAtIsNull(UUID createdByUserId, String workspaceId);

    @WorkspaceUnscoped("by-PK update; ownership already verified by the filter before dispatch")
    @Modifying
    @Query("update ApiKeyEntity k set k.lastUsedAt = :at, k.lastUsedIp = :ip where k.id = :id")
    int touchLastUsed(@Param("id") UUID id, @Param("at") OffsetDateTime at, @Param("ip") String ip);
}
