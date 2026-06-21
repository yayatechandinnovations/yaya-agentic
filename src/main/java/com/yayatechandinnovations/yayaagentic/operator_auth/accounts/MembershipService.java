package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService.RequestMeta;
import com.yayatechandinnovations.yayaagentic.persistence.UserMembershipEntity;
import com.yayatechandinnovations.yayaagentic.persistence.UserMembershipRepository;
import com.yayatechandinnovations.yayaagentic.persistence.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Membership lifecycle for a workspace from an actor's perspective.
 *
 * <p>Two invariants govern every write:
 * <ol>
 *   <li><b>Last-OWNER protection.</b> Demoting or removing an OWNER row
 *       fails with {@link LastOwnerException} (→ 409) if no other OWNER
 *       exists in the workspace.</li>
 *   <li><b>OWNER-only OWNER changes.</b> Only an existing OWNER may
 *       promote any member to OWNER or change another OWNER's role.</li>
 * </ol>
 * </p>
 *
 * <p>The controller layer is still responsible for calling
 * {@code WorkspaceAccessGuard.require(actor, workspace, ADMIN)} before
 * any of these methods — this service trusts that the actor is at least
 * an ADMIN of the workspace it operates on. ADMIN-vs-OWNER nuance is
 * enforced here.</p>
 */
@Service
public class MembershipService {

    private final UserMembershipRepository memberships;
    private final UserRepository users;
    private final AccountAuditWriter audit;

    public MembershipService(UserMembershipRepository memberships,
                             UserRepository users,
                             AccountAuditWriter audit) {
        this.memberships = memberships;
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<MemberSummary> list(TenantId workspace) {
        List<UserMembershipEntity> rows = memberships.findByWorkspaceId(workspace.value());
        if (rows.isEmpty()) return List.of();
        List<MemberSummary> out = new ArrayList<>(rows.size());
        for (UserMembershipEntity m : rows) {
            String email = users.findById(m.getUserId()).map(u -> u.getEmail()).orElse(null);
            String name  = users.findById(m.getUserId()).map(u -> u.getDisplayName()).orElse(null);
            out.add(new MemberSummary(
                    new UserId(m.getUserId()),
                    email,
                    name,
                    Role.valueOf(m.getRole()),
                    m.getAddedAt()));
        }
        return out;
    }

    /**
     * Change a member's role.
     *
     * @throws ResponseStatusException 404 if no such membership; 403 if
     *         ADMIN tries to touch an OWNER row or promote to OWNER
     * @throws LastOwnerException if this would leave the workspace without
     *         an OWNER
     */
    @Transactional
    public MemberSummary changeRole(TenantId workspace,
                                    UserId target,
                                    Role newRole,
                                    Membership actor,
                                    RequestMeta meta) {
        if (newRole == Role.PLATFORM_ADMIN) {
            throw new IllegalArgumentException("PLATFORM_ADMIN is not a workspace role");
        }
        UserMembershipEntity row = memberships
                .findByUserIdAndWorkspaceId(target.value(), workspace.value())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "member not found"));

        Role currentRole = Role.valueOf(row.getRole());
        if (currentRole == newRole) {
            // Idempotent no-op; still return the summary.
            return toSummary(row, currentRole);
        }

        // OWNER-only rule: only an existing OWNER (or platform admin) may
        // promote to OWNER, or change another OWNER's role.
        boolean actorIsOwnerTier = actor.role().atLeast(Role.OWNER);
        if (!actorIsOwnerTier && (currentRole == Role.OWNER || newRole == Role.OWNER)) {
            throw new ResponseStatusException(FORBIDDEN, "Only an owner can change owner roles.");
        }

        if (currentRole == Role.OWNER && newRole != Role.OWNER) {
            if (memberships.countOwners(workspace.value()) <= 1) {
                throw LastOwnerException.forDemoteSelf();
            }
        }

        row.setRole(newRole.name());
        audit.write(newRole.atLeast(currentRole) ? "ROLE_GRANT" : "ROLE_REVOKE",
                actor.userId(), workspace, meta,
                Map.of(
                        "target_user_id", target.value().toString(),
                        "from", currentRole.name(),
                        "to", newRole.name()));
        return toSummary(row, newRole);
    }

    /**
     * Remove a member.
     *
     * @throws ResponseStatusException 404 if no such membership; 403 if
     *         ADMIN tries to remove an OWNER
     * @throws LastOwnerException if this would leave the workspace without
     *         an OWNER
     */
    @Transactional
    public void remove(TenantId workspace,
                       UserId target,
                       Membership actor,
                       RequestMeta meta) {
        UserMembershipEntity row = memberships
                .findByUserIdAndWorkspaceId(target.value(), workspace.value())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "member not found"));

        Role currentRole = Role.valueOf(row.getRole());
        boolean actorIsOwnerTier = actor.role().atLeast(Role.OWNER);
        if (!actorIsOwnerTier && currentRole == Role.OWNER) {
            throw new ResponseStatusException(FORBIDDEN, "Only an owner can remove an owner.");
        }
        if (currentRole == Role.OWNER && memberships.countOwners(workspace.value()) <= 1) {
            throw LastOwnerException.forRemoveSelf();
        }

        memberships.delete(row);
        audit.write("ROLE_REVOKE", actor.userId(), workspace, meta,
                Map.of(
                        "target_user_id", target.value().toString(),
                        "from", currentRole.name(),
                        "removed", true));
    }

    // ------------------------------------------------------------------

    private static MemberSummary toSummary(UserMembershipEntity e, Role role) {
        return new MemberSummary(
                new UserId(e.getUserId()),
                null,
                null,
                role,
                e.getAddedAt());
    }

    public record MemberSummary(
            UserId userId,
            String email,
            String displayName,
            Role role,
            OffsetDateTime addedAt
    ) {
        public OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    }
}
