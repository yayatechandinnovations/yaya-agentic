package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

import java.time.OffsetDateTime;

/**
 * A registered admin-console user. Read-projection of a {@code users} row;
 * never carries the password hash.
 */
public record User(
        UserId id,
        String email,
        String displayName,
        UserStatus status,
        boolean isPlatformAdmin,
        OffsetDateTime emailVerifiedAt,
        OffsetDateTime lastLoginAt,
        OffsetDateTime createdAt
) {
}
