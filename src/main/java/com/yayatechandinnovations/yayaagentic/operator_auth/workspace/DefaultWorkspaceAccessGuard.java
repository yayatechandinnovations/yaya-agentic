package com.yayatechandinnovations.yayaagentic.operator_auth.workspace;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Membership;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.UserId;
import com.yayatechandinnovations.yayaagentic.persistence.UserEntity;
import com.yayatechandinnovations.yayaagentic.persistence.UserMembershipEntity;
import com.yayatechandinnovations.yayaagentic.persistence.UserMembershipRepository;
import com.yayatechandinnovations.yayaagentic.persistence.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Default predicate: one PK lookup against {@code user_workspace_memberships}
 * plus a platform-admin short-circuit. The guard is intentionally
 * audit-free — the controller advice that wraps {@code @WorkspaceScoped}
 * methods is responsible for emitting {@code PLATFORM_ADMIN_ACTION} rows
 * so the audit can carry the canonical action path.
 */
@Component
public class DefaultWorkspaceAccessGuard implements WorkspaceAccessGuard {

    private final UserMembershipRepository memberships;
    private final UserRepository users;

    public DefaultWorkspaceAccessGuard(UserMembershipRepository memberships,
                                       UserRepository users) {
        this.memberships = memberships;
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public Membership require(UserId user, TenantId workspace, Role atLeast) {
        if (user == null || workspace == null || atLeast == null) {
            throw new WorkspaceNotFoundException(workspace, user);
        }

        // Platform-admin short-circuit. We check this FIRST so a platform
        // admin can access archived / orphan workspaces during incident
        // response without first needing a membership row.
        if (isPlatformAdmin(user)) {
            return new Membership(user, workspace, Role.PLATFORM_ADMIN,
                    user, OffsetDateTime.now(ZoneOffset.UTC));
        }

        Optional<UserMembershipEntity> row = memberships
                .findByUserIdAndWorkspaceId(user.value(), workspace.value());
        if (row.isEmpty()) {
            throw new WorkspaceNotFoundException(workspace, user);
        }

        Role granted = Role.valueOf(row.get().getRole());
        if (!granted.atLeast(atLeast)) {
            // 404 (not 403) on insufficient role too — same existence-leak
            // mitigation. The caller can't tell whether they have no row
            // or just an inadequate one.
            throw new WorkspaceNotFoundException(workspace, user);
        }

        return toMembership(row.get(), granted);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<TenantId> visible(UserId user, Collection<TenantId> candidates) {
        if (user == null || candidates == null || candidates.isEmpty()) return Set.of();
        if (isPlatformAdmin(user)) return new HashSet<>(candidates);

        Set<String> mine = new HashSet<>();
        for (UserMembershipEntity m : memberships.findByUserId(user.value())) {
            mine.add(m.getWorkspaceId());
        }
        Set<TenantId> result = new HashSet<>();
        for (TenantId t : candidates) {
            if (mine.contains(t.value())) result.add(t);
        }
        return result;
    }

    private boolean isPlatformAdmin(UserId user) {
        return users.findById(user.value())
                .map(UserEntity::isPlatformAdmin)
                .orElse(false);
    }

    private static Membership toMembership(UserMembershipEntity e, Role role) {
        UserId addedBy = e.getAddedBy() == null ? null : new UserId(e.getAddedBy());
        return new Membership(
                new UserId(e.getUserId()),
                new TenantId(e.getWorkspaceId()),
                role,
                addedBy,
                e.getAddedAt());
    }
}
