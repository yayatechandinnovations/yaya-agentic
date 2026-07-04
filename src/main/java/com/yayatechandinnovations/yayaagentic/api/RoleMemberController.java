package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.persistence.RoleMemberEntity;
import com.yayatechandinnovations.yayaagentic.persistence.RoleMemberRepository;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/**
 * Admin management of the role roster that {@code ByRole} addressing fans out to
 * (§6, SM-3). The platform-managed roster (§18.3 v1 choice); membership is a
 * verified subject, so a shift-wide message reaches exactly the enrolled
 * operators.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/roles/{role}/members")
public class RoleMemberController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final RoleMemberRepository members;

    public RoleMemberController(TenantGuard tenantGuard,
                                WorkspaceAccessHelper workspaceAccess,
                                RoleMemberRepository members) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.members = members;
    }

    @GetMapping
    public List<String> list(@PathVariable("tenantId") String tenantId,
                             @PathVariable("role") String role,
                             ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        return members.findByTenantIdAndRole(tenantId, role).stream()
                .map(RoleMemberEntity::getSubject).toList();
    }

    @PutMapping("/{subject}")
    @Transactional
    public ResponseEntity<Void> add(@PathVariable("tenantId") String tenantId,
                                    @PathVariable("role") String role,
                                    @PathVariable("subject") String subject,
                                    ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        if (!members.existsById(new RoleMemberEntity.PK(tenantId, role, subject))) {
            members.save(new RoleMemberEntity(tenantId, role, subject));
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{subject}")
    @Transactional
    public ResponseEntity<Void> remove(@PathVariable("tenantId") String tenantId,
                                       @PathVariable("role") String role,
                                       @PathVariable("subject") String subject,
                                       ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        members.deleteById(new RoleMemberEntity.PK(tenantId, role, subject));
        return ResponseEntity.noContent().build();
    }
}
