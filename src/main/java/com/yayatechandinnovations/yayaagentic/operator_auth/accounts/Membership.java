package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;

import java.time.OffsetDateTime;

/**
 * Edge between a {@link User} and a workspace ({@link TenantId}) carrying a
 * {@link Role}. The single load-bearing predicate consulted by the access
 * guard — no row means "no access, render 404."
 */
public record Membership(
        UserId userId,
        TenantId workspaceId,
        Role role,
        UserId addedBy,
        OffsetDateTime addedAt
) {
}
