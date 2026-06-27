package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.UserId;
import com.yayatechandinnovations.yayaagentic.persistence.ApiKeyEntity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Immutable view of an API-key row — the value cached by the verifier and the
 * shape services hand to controllers. Never carries the secret (that exists
 * only transiently at mint time). See {@code docs/design/api-keys-design.md}.
 */
public record ApiKey(
        UUID id,
        TenantId workspaceId,
        String name,
        String keyPrefix,
        Role role,
        UserId createdByUserId,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime lastUsedAt,
        String lastUsedIp,
        OffsetDateTime revokedAt
) {

    public boolean revoked() { return revokedAt != null; }

    public boolean expired(OffsetDateTime now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public ApiKeyStatus status(OffsetDateTime now) {
        if (revoked()) return ApiKeyStatus.REVOKED;
        if (expired(now)) return ApiKeyStatus.EXPIRED;
        return ApiKeyStatus.ACTIVE;
    }

    public ApiKeyStatus status() {
        return status(OffsetDateTime.now(ZoneOffset.UTC));
    }

    public static ApiKey from(ApiKeyEntity e) {
        return new ApiKey(
                e.getId(),
                new TenantId(e.getWorkspaceId()),
                e.getName(),
                e.getKeyPrefix(),
                Role.valueOf(e.getRole()),
                e.getCreatedByUserId() == null ? null : new UserId(e.getCreatedByUserId()),
                e.getCreatedAt(),
                e.getExpiresAt(),
                e.getLastUsedAt(),
                e.getLastUsedIp(),
                e.getRevokedAt());
    }
}
