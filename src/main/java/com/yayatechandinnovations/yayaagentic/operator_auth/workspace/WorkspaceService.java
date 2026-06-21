package com.yayatechandinnovations.yayaagentic.operator_auth.workspace;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.AccountAuditWriter;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.UserId;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.WorkspaceSlugger;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.persistence.UserMembershipEntity;
import com.yayatechandinnovations.yayaagentic.persistence.UserMembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read + write surface for workspaces from the caller's perspective —
 * always tied to a {@link UserId}, always emitting audit. Distinct from
 * {@code TenantController}, which exposes the platform-admin "every tenant
 * in the system" view.
 */
@Service
public class WorkspaceService {

    private final TenantRepository tenants;
    private final UserMembershipRepository memberships;
    private final WorkspaceSlugger slugger;
    private final AccountAuditWriter audit;

    public WorkspaceService(TenantRepository tenants,
                            UserMembershipRepository memberships,
                            WorkspaceSlugger slugger,
                            AccountAuditWriter audit) {
        this.tenants = tenants;
        this.memberships = memberships;
        this.slugger = slugger;
        this.audit = audit;
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /** Returns the caller's memberships, each enriched with the workspace summary. */
    @Transactional(readOnly = true)
    public List<MyWorkspace> listMine(UserId user) {
        List<UserMembershipEntity> mine = memberships.findByUserId(user.value());
        if (mine.isEmpty()) return List.of();
        Map<String, MyWorkspace> built = new LinkedHashMap<>();
        for (UserMembershipEntity m : mine) {
            tenants.findById(m.getWorkspaceId()).ifPresent(t ->
                    built.put(t.getId(), new MyWorkspace(toSummary(t), Role.valueOf(m.getRole()))));
        }
        return new ArrayList<>(built.values());
    }

    /** Workspace detail by id-or-code. Returns empty if missing — the caller still gates with the guard. */
    @Transactional(readOnly = true)
    public Optional<WorkspaceSummary> findByIdOrCode(String idOrCode) {
        return resolve(idOrCode).map(this::toSummary);
    }

    /**
     * Try to resolve a path-segment to a workspace row. Currently the
     * primary key IS the code, so this is a single-column lookup. Once
     * V22→V24 split id and code, parse {@code idOrCode} as a UUID first and
     * fall back to the code lookup.
     */
    @Transactional(readOnly = true)
    public Optional<TenantId> resolveToId(String idOrCode) {
        return resolve(idOrCode).map(t -> new TenantId(t.getId()));
    }

    private Optional<TenantEntity> resolve(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) return Optional.empty();
        return tenants.findById(idOrCode);
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /** Mint a new workspace owned by {@code actor}. Emits WORKSPACE_CREATE. */
    @Transactional
    public WorkspaceSummary create(UserId actor, String displayName, OperatorAccountService.RequestMeta meta) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName is required");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String slug = slugger.allocate(displayName, tenants::existsById);

        TenantEntity t = new TenantEntity(slug, displayName.trim());
        t.setCreatedBy(actor.toString());
        t.setCode(slug);
        t.setOwnerUserId(actor.value());
        tenants.save(t);

        memberships.save(new UserMembershipEntity(
                actor.value(),
                slug,
                Role.OWNER.name(),
                actor.value(),
                now));

        audit.write("WORKSPACE_CREATE", actor, new TenantId(slug), meta,
                Map.of("code", slug, "source", "explicit"));
        return toSummary(t);
    }

    /** Rename. Only changes {@code displayName} — {@code code} is immutable in M5a. */
    @Transactional
    public WorkspaceSummary rename(TenantId workspace, String newDisplayName,
                                   UserId actor, OperatorAccountService.RequestMeta meta) {
        if (newDisplayName == null || newDisplayName.isBlank()) {
            throw new IllegalArgumentException("displayName is required");
        }
        TenantEntity t = tenants.findById(workspace.value())
                .orElseThrow(() -> new WorkspaceNotFoundException(workspace, actor));
        String old = t.getDisplayName();
        t.setDisplayName(newDisplayName.trim());
        audit.write("WORKSPACE_RENAME", actor, workspace, meta,
                Map.of("old", old == null ? "" : old, "new", t.getDisplayName()));
        return toSummary(t);
    }

    /**
     * Soft archive. Requires {@code confirmCode} to match the workspace's
     * own code exactly — typed-name confirmation matches the destructive
     * UI affordance in UI-UX §4.9 / §5.6.
     */
    @Transactional
    public void archive(TenantId workspace, String confirmCode,
                        UserId actor, OperatorAccountService.RequestMeta meta) {
        TenantEntity t = tenants.findById(workspace.value())
                .orElseThrow(() -> new WorkspaceNotFoundException(workspace, actor));
        if (confirmCode == null || !confirmCode.equals(t.getId())) {
            throw new IllegalArgumentException("confirmCode does not match the workspace code");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        t.setStatus(TenantEntity.Status.ARCHIVED);
        t.setArchivedAt(now);
        audit.write("WORKSPACE_ARCHIVE", actor, workspace, meta, Map.of());
    }

    // ------------------------------------------------------------------

    private WorkspaceSummary toSummary(TenantEntity t) {
        // Prefer the explicit code column once V22 has populated it; fall back
        // to the slug PK so pre-migration rows still render.
        String code = t.getCode() != null ? t.getCode() : t.getId();
        return new WorkspaceSummary(
                t.getId(),
                code,
                t.getDisplayName(),
                t.getStatus(),
                t.getArchivedAt(),
                t.getCreatedAt());
    }

    /**
     * Workspace summary shape. {@code id} and {@code code} happen to be the
     * same string today (since {@code tenants.id} is the slug) — V24 will
     * split them; the field is here now so the UI contract is stable.
     */
    public record WorkspaceSummary(
            String id,
            String code,
            String displayName,
            String status,
            OffsetDateTime archivedAt,
            OffsetDateTime createdAt
    ) {}

    /** Listing item: the workspace plus the caller's role in it. */
    public record MyWorkspace(WorkspaceSummary workspace, Role role) {}
}
