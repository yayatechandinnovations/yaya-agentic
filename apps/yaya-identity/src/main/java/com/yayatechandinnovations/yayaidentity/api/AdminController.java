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
import com.yayatechandinnovations.yayaidentity.platform.Ids.PermissionKey;
import com.yayatechandinnovations.yayaidentity.platform.Ids.RoleId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import com.yayatechandinnovations.yayaidentity.roles.AssignmentSubject;
import com.yayatechandinnovations.yayaidentity.roles.PermissionCatalogService;
import com.yayatechandinnovations.yayaidentity.roles.Role;
import com.yayatechandinnovations.yayaidentity.roles.RoleAssignmentService;
import com.yayatechandinnovations.yayaidentity.roles.RoleService;
import com.yayatechandinnovations.yayaidentity.roles.Scope;
import com.yayatechandinnovations.yayaidentity.users.User;
import com.yayatechandinnovations.yayaidentity.users.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The identity admin API (design §9.3) — orgs, users, groups, roles, scoped
 * assignments, and clients. Token-protected by the resource-server chain; the
 * console (I5 frontend) drives it. Full per-request PDP-gating is a follow-up.
 * List/read DTOs are deliberately rich (names, emails, counts, permissions) so
 * the console can offer pickers instead of raw-id inputs.
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
    private final PermissionCatalogService permissions;

    public AdminController(OrganizationService orgs, InvitationService invitations, UserService users,
                          RoleService roles, RoleAssignmentService assignments, GroupService groups,
                          ClientService clients, PermissionCatalogService permissions) {
        this.orgs = orgs;
        this.invitations = invitations;
        this.users = users;
        this.roles = roles;
        this.assignments = assignments;
        this.groups = groups;
        this.clients = clients;
        this.permissions = permissions;
    }

    // --- organizations ---

    public record CreateOrgDto(String name, String description, String contactEmail,
                               String contactPhone, String website, String country) {}
    public record UpdateOrgDto(String name, String description, String contactEmail,
                               String contactPhone, String website, String country) {}
    public record InviteDto(String email, String role, Integer ttlDays) {}
    public record MfaDto(boolean required) {}

    @PostMapping("/orgs")
    public Map<String, Object> createOrg(@RequestBody CreateOrgDto body) {
        Organization o = orgs.create(body.name());
        Organization.Profile profile = new Organization.Profile(
                body.description(), body.contactEmail(), body.contactPhone(),
                body.website(), body.country());
        if (!Organization.Profile.EMPTY.equals(profile)) {
            o = orgs.update(o.id(), o.name(), profile);
        }
        return orgDto(o);
    }

    @GetMapping("/orgs")
    public List<Map<String, Object>> listOrgs() {
        return orgs.list().stream().map(this::orgDto).toList();
    }

    @GetMapping("/orgs/{org}")
    public Map<String, Object> getOrg(@PathVariable String org) {
        return orgs.byId(new OrgId(org)).map(this::orgDto)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such org"));
    }

    @PutMapping("/orgs/{org}")
    public Map<String, Object> updateOrg(@PathVariable String org, @RequestBody UpdateOrgDto body) {
        if (body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        Organization o = orgs.update(new OrgId(org), body.name().trim(),
                new Organization.Profile(body.description(), body.contactEmail(),
                        body.contactPhone(), body.website(), body.country()));
        return orgDto(o);
    }

    private Map<String, Object> orgDto(Organization o) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", o.id().value());
        dto.put("name", o.name());
        dto.put("slug", o.slug());
        dto.put("status", o.status().name());
        dto.put("mfaRequired", o.mfaRequired());
        dto.put("description", o.profile().description());
        dto.put("contactEmail", o.profile().contactEmail());
        dto.put("contactPhone", o.profile().contactPhone());
        dto.put("website", o.profile().website());
        dto.put("country", o.profile().country());
        dto.put("createdAt", o.createdAt());
        dto.put("memberCount", orgs.memberCount(o.id()));
        return dto;
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

    @GetMapping("/orgs/{org}/members")
    public List<Map<String, Object>> members(@PathVariable String org) {
        return orgs.membersOf(new OrgId(org)).stream()
                .map(m -> memberDto(m.user(), m.status().name()))
                .toList();
    }

    /** Membership rows joined with the user record — the console shows people, not ids. */
    private Map<String, Object> memberDto(UserId userId, String membershipStatus) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("user", userId.value());
        dto.put("status", membershipStatus);
        users.byId(userId).ifPresent(u -> {
            dto.put("email", u.primaryEmail());
            dto.put("displayName", u.displayName());
            dto.put("kind", u.kind().name());
            dto.put("userStatus", u.status().name());
        });
        return dto;
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
        return roleDto(r);
    }

    /** Org roles plus the built-in system roles — both are assignable, so both are listed. */
    @GetMapping("/orgs/{org}/roles")
    public List<Map<String, Object>> listRoles(@PathVariable String org) {
        return Stream.concat(roles.systemRoles().stream(), roles.listRoles(new OrgId(org)).stream())
                .map(this::roleDto)
                .toList();
    }

    private Map<String, Object> roleDto(Role r) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", r.id().value());
        dto.put("name", r.name());
        dto.put("system", r.system());
        dto.put("permissions", roles.permissionsOf(r.id()).stream()
                .map(PermissionKey::value).sorted().toList());
        return dto;
    }

    /** The declared permission catalog across apps — powers the role-composition picker. */
    @GetMapping("/permissions")
    public List<String> permissionCatalog() {
        return permissions.listAll();
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

    @GetMapping("/orgs/{org}/groups")
    public List<Map<String, Object>> listGroups(@PathVariable String org) {
        return groups.listGroups(new OrgId(org)).stream()
                .map(g -> Map.<String, Object>of("id", g.id().value(), "name", g.name(),
                        "memberCount", groups.membersOf(g.id()).size()))
                .toList();
    }

    @GetMapping("/groups/{gid}/members")
    public List<Map<String, Object>> groupMembers(@PathVariable String gid) {
        return groups.membersOf(new GroupId(gid)).stream()
                .map(u -> memberDto(u, "MEMBER"))
                .toList();
    }

    @PutMapping("/groups/{gid}/members/{userId}")
    public Map<String, Object> addGroupMember(@PathVariable String gid, @PathVariable String userId) {
        groups.addMember(new GroupId(gid), new UserId(userId));
        return Map.of("group", gid, "user", userId);
    }

    @DeleteMapping("/groups/{gid}/members/{userId}")
    public Map<String, Object> removeGroupMember(@PathVariable String gid, @PathVariable String userId) {
        groups.removeMember(new GroupId(gid), new UserId(userId));
        return Map.of("group", gid, "user", userId, "status", "removed");
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

    public record RedirectUrisDto(List<String> add) {}

    /** Add-only — every console app that reuses a public client registers its callback here. */
    @PutMapping("/clients/{clientId}/redirect-uris")
    public Map<String, Object> addRedirectUris(@PathVariable String clientId,
                                               @RequestBody RedirectUrisDto body) {
        if (body.add() == null || body.add().isEmpty()
                || body.add().stream().anyMatch(u -> u == null || !u.matches("https?://\\S+"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "add: one or more absolute http(s) redirect URIs");
        }
        try {
            Client c = clients.addRedirectUris(clientId, body.add());
            return Map.of("clientId", c.clientId(), "redirectUris", c.redirectUris());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping("/clients")
    public List<Map<String, Object>> listClients() {
        return clients.list().stream()
                .map(c -> Map.<String, Object>of("clientId", c.clientId(), "type", c.type().name(),
                        "audiences", c.audiences(), "redirectUris", c.redirectUris()))
                .toList();
    }
}
