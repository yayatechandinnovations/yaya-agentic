package com.yayatechandinnovations.yayaidentity.roles;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.RoleId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "role_assignments")
public class RoleAssignmentEntity {

    @Id
    private String id;

    @Column(name = "org_id", nullable = false)
    private String orgId;

    @Column(name = "subject_kind", nullable = false)
    private String subjectKind;

    @Column(name = "subject_id", nullable = false)
    private String subjectId;

    @Column(name = "role_id", nullable = false)
    private String roleId;

    @Column(name = "scope_kind", nullable = false)
    private String scopeKind;

    @Column(name = "scope_type")
    private String scopeType;

    @Column(name = "scope_id")
    private String scopeId;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    protected RoleAssignmentEntity() {}

    public RoleAssignmentEntity(String id, String orgId, AssignmentSubject subject, String roleId,
                                Scope scope, Instant grantedAt) {
        this.id = id;
        this.orgId = orgId;
        this.subjectKind = subject.kind().name();
        this.subjectId = subject.id();
        this.roleId = roleId;
        this.grantedAt = grantedAt;
        switch (scope) {
            case Scope.Org org -> {
                this.scopeKind = "ORG";
                this.scopeId = org.org().value();
            }
            case Scope.Resource r -> {
                this.scopeKind = "RESOURCE";
                this.scopeType = r.type();
                this.scopeId = r.id();
            }
        }
    }

    public boolean isOrgScoped() {
        return "ORG".equals(scopeKind);
    }

    /** True if this assignment's scope covers the given target scope (design §5.5). */
    public boolean covers(Scope target) {
        if (isOrgScoped()) {
            return true;   // org-wide grant covers any resource in the org
        }
        return target instanceof Scope.Resource r
                && r.type().equals(scopeType) && r.id().equals(scopeId);
    }

    public Scope scope() {
        return isOrgScoped()
                ? new Scope.Org(new OrgId(scopeId))
                : new Scope.Resource(scopeType, scopeId);
    }

    public RoleAssignment toDomain() {
        return new RoleAssignment(id, new OrgId(orgId),
                new AssignmentSubject(AssignmentSubject.Kind.valueOf(subjectKind), subjectId),
                new RoleId(roleId), scope(), grantedAt);
    }

    public String getId() { return id; }
    public String getRoleId() { return roleId; }
}
