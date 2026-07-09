package com.yayatechandinnovations.yayaidentity.api;

import com.yayatechandinnovations.yayaidentity.clients.Client;
import com.yayatechandinnovations.yayaidentity.clients.ClientService;
import com.yayatechandinnovations.yayaidentity.groups.Group;
import com.yayatechandinnovations.yayaidentity.groups.GroupService;
import com.yayatechandinnovations.yayaidentity.organizations.InvitationService;
import com.yayatechandinnovations.yayaidentity.organizations.Organization;
import com.yayatechandinnovations.yayaidentity.organizations.OrganizationService;
import com.yayatechandinnovations.yayaidentity.platform.Ids.GroupId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.RoleId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import com.yayatechandinnovations.yayaidentity.roles.AssignmentSubject;
import com.yayatechandinnovations.yayaidentity.roles.Role;
import com.yayatechandinnovations.yayaidentity.roles.RoleAssignmentService;
import com.yayatechandinnovations.yayaidentity.roles.RoleService;
import com.yayatechandinnovations.yayaidentity.roles.Scope;
import com.yayatechandinnovations.yayaidentity.users.User;
import com.yayatechandinnovations.yayaidentity.users.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The identity admin API (design §9.3) — orgs, users, groups, roles, scoped
 * assignments, and clients. Token-protected by the resource-server chain; the
 * console (I5 frontend) drives it. Full per-request PDP-gating is a follow-up.
 */
@RestController
@RequestMapping("/v1/identity/admin")
public class AdminController {

    private final OrganizationService orgs;
    private final InvitationService invitations;
    private final UserService users;
    private final RoleService roles;
    private final RoleAssignmentService assignments;
    private final GroupService groups;
    private final ClientService clients;

    public AdminController(OrganizationService orgs, InvitationService invitations, UserService users,
                          RoleService roles, RoleAssignmentService assignments, GroupService groups,
                          ClientService clients) {
        this.orgs = orgs;
        this.invitations = invitations;
        this.users = users;
        this.roles = roles;
        this.assignments = assignments;
        this.groups = groups;
        this.clients = clients;
    }

    // --- organizations ---

    public record CreateOrgDto(String name) {}
    public record InviteDto(String email, String role, Integer ttlDays) {}
    public record MfaDto(boolean required) {}

    @PostMapping("/orgs")
    public Map<String, Object> createOrg(@RequestBody CreateOrgDto body) {
        Organization o = orgs.create(body.name());
        return Map.of("id", o.id().value(), "name", o.name(), "slug", o.slug());
    }

    @org.springframework.web.bind.annotation.GetMapping("/orgs")
    public List<Map<String, Object>> listOrgs() {
        return orgs.list().stream()
                .map(o -> Map.<String, Object>of("id", o.id().value(), "name", o.name(),
                        "slug", o.slug(), "mfaRequired", o.mfaRequired()))
                .toList();
    }

    @org.springframework.web.bind.annotation.GetMapping("/orgs/{org}")
    public Map<String, Object> getOrg(@PathVariable String org) {
        return orgs.byId(new OrgId(org))
                .map(o -> Map.<String, Object>of("id", o.id().value(), "name", o.name(),
                        "slug", o.slug(), "mfaRequired", o.mfaRequired()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such org"));
    }

    @org.springframework.web.bind.annotation.GetMapping("/orgs/{org}/groups")
    public List<Map<String, Object>> listGroups(@PathVariable String org) {
        return groups.listGroups(new OrgId(org)).stream()
                .map(g -> Map.<String, Object>of("id", g.id().value(), "name", g.name()))
                .toList();
    }

    @PutMapping("/orgs/{org}/mfa")
    public Map<String, Object> setMfa(@PathVariable String org, @RequestBody MfaDto body) {
        orgs.setMfaRequired(new OrgId(org), body.required());
        return Map.of("org", org, "mfaRequired", body.required());
    }

    @PostMapping("/orgs/{org}/invitations")
    public Map<String, Object> invite(@PathVariable String org, @RequestBody InviteDto body) {
        var result = invitations.invite(new OrgId(org), body.email(), body.role(), null,
                Duration.ofDays(body.ttlDays() == null ? 7 : body.ttlDays()));
        return Map.of("invitationId", result.invitation().id(), "token", result.token());
    }

    @org.springframework.web.bind.annotation.GetMapping("/orgs/{org}/members")
    public List<Map<String, Object>> members(@PathVariable String org) {
        return orgs.membersOf(new OrgId(org)).stream()
                .map(m -> Map.<String, Object>of("user", m.user().value(), "status", m.status().name()))
                .toList();
    }

    // --- users ---

    public record CreateUserDto(String email, String phone, String displayName) {}

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody CreateUserDto body) {
        User u = users.createPerson(body.email(), body.phone(), body.displayName());
        return Map.of("id", u.id().value(), "status", u.status().name());
    }

    @PostMapping("/users/{id}/lock")
    public Map<String, Object> lock(@PathVariable String id) {
        return Map.of("id", id, "status", users.setStatus(new UserId(id), User.Status.LOCKED).status().name());
    }

    @PostMapping("/users/{id}/disable")
    public Map<String, Object> disable(@PathVariable String id) {
        return Map.of("id", id, "status", users.setStatus(new UserId(id), User.Status.DISABLED).status().name());
    }

    @DeleteMapping("/users/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        users.deleteUser(new UserId(id));
        return Map.of("id", id, "status", "DELETED");
    }

    // --- roles & scoped assignments ---

    public record CreateRoleDto(String name, List<String> permissions) {}
    public record AssignDto(String subjectKind, String subjectId, String roleId,
                            String scopeType, String scopeId) {}

    @PostMapping("/orgs/{org}/roles")
    public Map<String, Object> createRole(@PathVariable String org, @RequestBody CreateRoleDto body) {
        Role r = roles.createRole(new OrgId(org), body.name(),
                body.permissions() == null ? List.of() : body.permissions());
        return Map.of("id", r.id().value(), "name", r.name());
    }

    @org.springframework.web.bind.annotation.GetMapping("/orgs/{org}/roles")
    public List<Map<String, Object>> listRoles(@PathVariable String org) {
        return roles.listRoles(new OrgId(org)).stream()
                .map(r -> Map.<String, Object>of("id", r.id().value(), "name", r.name()))
                .toList();
    }

    @PostMapping("/orgs/{org}/role-assignments")
    public Map<String, Object> assign(@PathVariable String org, @RequestBody AssignDto body) {
        AssignmentSubject subject = new AssignmentSubject(
                AssignmentSubject.Kind.valueOf(body.subjectKind()), body.subjectId());
        Scope scope = body.scopeType() != null && body.scopeId() != null
                ? new Scope.Resource(body.scopeType(), body.scopeId())
                : new Scope.Org(new OrgId(org));
        assignments.assign(new OrgId(org), subject, new RoleId(body.roleId()), scope);
        return Map.of("status", "assigned");
    }

    // --- groups ---

    public record CreateGroupDto(String name) {}

    @PostMapping("/orgs/{org}/groups")
    public Map<String, Object> createGroup(@PathVariable String org, @RequestBody CreateGroupDto body) {
        Group g = groups.create(new OrgId(org), body.name());
        return Map.of("id", g.id().value(), "name", g.name());
    }

    @PutMapping("/groups/{gid}/members/{userId}")
    public Map<String, Object> addGroupMember(@PathVariable String gid, @PathVariable String userId) {
        groups.addMember(new GroupId(gid), new UserId(userId));
        return Map.of("group", gid, "user", userId);
    }

    // --- clients / service accounts ---

    public record CreateClientDto(String clientId, String secret, List<String> audiences, List<String> scopes) {}
    public record RotateDto(String secret) {}

    @PostMapping("/clients")
    public Map<String, Object> createClient(@RequestBody CreateClientDto body) {
        Client c = clients.registerServiceAccount(null, body.clientId(), body.secret(),
                body.audiences() == null ? List.of() : body.audiences(),
                body.scopes() == null ? List.of() : body.scopes());
        return Map.of("clientId", c.clientId(), "type", c.type().name());
    }

    @PostMapping("/clients/{clientId}/rotate-secret")
    public Map<String, Object> rotate(@PathVariable String clientId, @RequestBody RotateDto body) {
        clients.rotateSecret(clientId, body.secret());
        return Map.of("clientId", clientId, "status", "rotated");
    }

    @org.springframework.web.bind.annotation.GetMapping("/clients")
    public List<Map<String, Object>> listClients() {
        return clients.list().stream()
                .map(c -> Map.<String, Object>of("clientId", c.clientId(), "type", c.type().name(),
                        "audiences", c.audiences()))
                .toList();
    }
}
