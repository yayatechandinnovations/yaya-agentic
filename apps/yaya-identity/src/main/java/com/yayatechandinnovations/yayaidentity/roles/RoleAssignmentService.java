package com.yayatechandinnovations.yayaidentity.roles;

import com.yayatechandinnovations.yayaidentity.groups.GroupService;
import com.yayatechandinnovations.yayaidentity.platform.Ids.GroupId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.PermissionKey;
import com.yayatechandinnovations.yayaidentity.platform.Ids.RoleId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import com.yayatechandinnovations.yayaidentity.platform.outbox.OutboxRelay;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Scoped role assignment and effective-permission resolution — the RBAC core
 * (design §5.5, FR-3.3/3.4). A subject's effective permissions for a target are
 * the union of permissions from every assignment (direct or via a group) whose
 * scope covers that target.
 */
@Service
public class RoleAssignmentService {

    private final RoleAssignmentRepository assignments;
    private final RoleService roles;
    private final GroupService groups;
    private final OutboxRelay outbox;

    public RoleAssignmentService(RoleAssignmentRepository assignments, RoleService roles,
                                 GroupService groups, OutboxRelay outbox) {
        this.assignments = assignments;
        this.roles = roles;
        this.groups = groups;
        this.outbox = outbox;
    }

    @Transactional
    public void assign(OrgId org, AssignmentSubject subject, RoleId role, Scope scope) {
        RoleAssignmentEntity e = new RoleAssignmentEntity(
                "asg_" + UUID.randomUUID().toString().replace("-", ""),
                org.value(), subject, role.value(), scope, Instant.now());
        try {
            assignments.saveAndFlush(e);
            outbox.append("role_assignment", e.getId(), "role.assigned",
                    "{\"org\":\"" + org.value() + "\",\"subject\":\"" + subject.id()
                            + "\",\"role\":\"" + role.value() + "\",\"scope\":\"" + scope.asClaim() + "\"}");
        } catch (DataIntegrityViolationException duplicate) {
            // Idempotent: the same (subject, role, scope) grant already exists.
        }
    }

    @Transactional
    public void revoke(String assignmentId) {
        assignments.deleteById(assignmentId);
        outbox.append("role_assignment", assignmentId, "role.revoked", "{\"id\":\"" + assignmentId + "\"}");
    }

    /** All assignments a user holds in an org — directly and via their groups. */
    @Transactional(readOnly = true)
    public List<RoleAssignmentEntity> assignmentsOf(UserId user, OrgId org) {
        List<RoleAssignmentEntity> all = new ArrayList<>(
                assignments.findByOrgIdAndSubjectKindAndSubjectId(org.value(), "USER", user.value()));
        List<String> groupIds = groups.groupIdsOf(user).stream().map(GroupId::value).toList();
        if (!groupIds.isEmpty()) {
            all.addAll(assignments.findByOrgIdAndSubjectKindAndSubjectIdIn(org.value(), "GROUP", groupIds));
        }
        return all;
    }

    /** Effective permissions for a target scope (FR-3.4). */
    @Transactional(readOnly = true)
    public Set<PermissionKey> effectivePermissions(UserId user, OrgId org, Scope target) {
        Set<PermissionKey> result = new HashSet<>();
        for (RoleAssignmentEntity a : assignmentsOf(user, org)) {
            if (a.covers(target)) {
                result.addAll(roles.permissionsOf(new RoleId(a.getRoleId())));
            }
        }
        return result;
    }

    /** Scoped roles for the token {@code roles} claim (FR-3.5): {@code [{role, scope}, …]}. */
    @Transactional(readOnly = true)
    public List<Map<String, String>> tokenRoles(UserId user, OrgId org) {
        List<Map<String, String>> claim = new ArrayList<>();
        for (RoleAssignmentEntity a : assignmentsOf(user, org)) {
            String roleName = roles.byId(new RoleId(a.getRoleId()))
                    .map(r -> r.name()).orElse(a.getRoleId());
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("role", roleName);
            entry.put("scope", a.scope().asClaim());
            claim.add(entry);
        }
        return claim;
    }
}
