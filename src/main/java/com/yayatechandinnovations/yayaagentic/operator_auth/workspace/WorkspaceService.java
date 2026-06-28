package com.yayatechandinnovations.yayaagentic.operator_auth.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.yayatechandinnovations.yayaagentic.tenant.BaseUrlValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Read + write surface for workspaces from the caller's perspective —
 * always tied to a {@link UserId}, always emitting audit. Distinct from
 * {@code TenantController}, which exposes the platform-admin "every tenant
 * in the system" view.
 */
@Service
public class WorkspaceService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final TenantRepository tenants;
    private final UserMembershipRepository memberships;
    private final WorkspaceSlugger slugger;
    private final AccountAuditWriter audit;
    private final ObjectMapper json;

    public WorkspaceService(TenantRepository tenants,
                            UserMembershipRepository memberships,
                            WorkspaceSlugger slugger,
                            AccountAuditWriter audit,
                            ObjectMapper json) {
        this.tenants = tenants;
        this.memberships = memberships;
        this.slugger = slugger;
        this.audit = audit;
        this.json = json;
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

    /**
     * Mint a new workspace owned by {@code actor}. When {@code explicitCode}
     * is non-null/non-blank it is validated and used verbatim (so the code
     * can match an external integration byte-for-byte); otherwise a slug is
     * derived from {@code displayName} as before. Collisions on the explicit
     * path raise {@link WorkspaceCodeConflictException} → 409. Emits
     * WORKSPACE_CREATE with the audit {@code source} discriminating
     * {@code explicit} vs {@code derived}.
     */
    @Transactional
    public WorkspaceSummary create(UserId actor,
                                   String displayName,
                                   String explicitCode,
                                   OperatorAccountService.RequestMeta meta) {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName is required");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        String code;
        String source;
        if (explicitCode != null && !explicitCode.isBlank()) {
            String trimmed = explicitCode.trim();
            slugger.validateExplicit(trimmed);
            if (tenants.existsById(trimmed)) {
                throw new WorkspaceCodeConflictException(trimmed);
            }
            code = trimmed;
            source = "explicit";
        } else {
            code = slugger.allocate(displayName, tenants::existsById);
            source = "derived";
        }

        TenantEntity t = new TenantEntity(code, displayName.trim());
        t.setCreatedBy(actor.toString());
        t.setCode(code);
        t.setOwnerUserId(actor.value());
        tenants.save(t);

        memberships.save(new UserMembershipEntity(
                actor.value(),
                code,
                Role.OWNER.name(),
                actor.value(),
                now));

        audit.write("WORKSPACE_CREATE", actor, new TenantId(code), meta,
                Map.of("code", code, "source", source));
        return toSummary(t);
    }

    /** Back-compat overload — derives the code from {@code displayName}. */
    @Transactional
    public WorkspaceSummary create(UserId actor, String displayName, OperatorAccountService.RequestMeta meta) {
        return create(actor, displayName, null, meta);
    }

    /**
     * Patch the workspace. Each argument is optional ({@code null} → "leave
     * untouched"); the only exception is {@code newDisplayName}, which when
     * present must be non-blank.
     *
     * <p>The three URL fields are independent:
     * <ul>
     *   <li>{@code hostBaseUrl} — outbound. Where HTTP tools call.</li>
     *   <li>{@code hostBaseUrlAllowlist} — outbound. Per-call override
     *       allowlist for {@code X-Yaya-Host-Base-Url}.</li>
     *   <li>{@code inboundOriginAllowlist} — INBOUND. Web origins
     *       allowed to hit {@code /v1/sessions/*}. Empty list +
     *       configured {@code hostBaseUrl} = origin derived from the
     *       base URL is the only allowed origin (see
     *       {@code OriginEnforcer}); empty list + empty
     *       {@code hostBaseUrl} = enforcement skipped entirely
     *       (auth still applies).</li>
     * </ul>
     * </p>
     *
     * <p>Note: {@code code} is still immutable in M5a.</p>
     */
    @Transactional
    public WorkspaceSummary patch(TenantId workspace,
                                  String newDisplayName,
                                  String newHostBaseUrl,
                                  List<String> newHostBaseUrlAllowlist,
                                  List<String> newInboundOriginAllowlist,
                                  UserId actor,
                                  OperatorAccountService.RequestMeta meta) {
        TenantEntity t = tenants.findById(workspace.value())
                .orElseThrow(() -> new WorkspaceNotFoundException(workspace, actor));
        boolean requireHttps = t.isRequireHttps();
        Map<String, Object> changes = new LinkedHashMap<>();

        if (newDisplayName != null) {
            if (newDisplayName.isBlank()) {
                throw new IllegalArgumentException("displayName is required");
            }
            String trimmed = newDisplayName.trim();
            if (!Objects.equals(trimmed, t.getDisplayName())) {
                changes.put("displayName.old", nullToEmpty(t.getDisplayName()));
                changes.put("displayName.new", trimmed);
                t.setDisplayName(trimmed);
            }
        }
        if (newHostBaseUrl != null) {
            // Empty string == clear; non-empty must pass shape validation.
            String trimmed = newHostBaseUrl.isBlank() ? null : newHostBaseUrl.trim();
            if (trimmed != null) {
                BaseUrlValidator.validateHostBaseUrl(trimmed, requireHttps);
            }
            if (!Objects.equals(trimmed, t.getHostBaseUrl())) {
                changes.put("hostBaseUrl.changed", "true");
                t.setHostBaseUrl(trimmed);
            }
        }
        if (newHostBaseUrlAllowlist != null) {
            for (String entry : newHostBaseUrlAllowlist) {
                BaseUrlValidator.validateUrlPattern(entry, requireHttps);
            }
            String newJson = writeStringList(newHostBaseUrlAllowlist);
            if (!Objects.equals(newJson, t.getHostBaseUrlAllowlistJson())) {
                changes.put("hostBaseUrlAllowlist.changed", "true");
                t.setHostBaseUrlAllowlistJson(newJson);
            }
        }
        if (newInboundOriginAllowlist != null) {
            for (String entry : newInboundOriginAllowlist) {
                BaseUrlValidator.validateOriginPattern(entry, requireHttps);
            }
            String newJson = writeStringList(newInboundOriginAllowlist);
            if (!Objects.equals(newJson, t.getInboundOriginAllowlistJson())) {
                changes.put("inboundOriginAllowlist.changed", "true");
                t.setInboundOriginAllowlistJson(newJson);
            }
        }

        if (!changes.isEmpty()) {
            audit.write("WORKSPACE_UPDATE", actor, workspace, meta, changes);
        }
        return toSummary(t);
    }

    /**
     * Legacy single-field convenience. Kept for callers that only want to
     * touch the display name (e.g. tests). New code should use
     * {@link #patch} so it can ship the full patch surface.
     */
    public WorkspaceSummary rename(TenantId workspace, String newDisplayName,
                                   UserId actor, OperatorAccountService.RequestMeta meta) {
        return patch(workspace, newDisplayName, null, null, null, actor, meta);
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
                t.getHostBaseUrl(),
                readStringList(t.getHostBaseUrlAllowlistJson()),
                readStringList(t.getInboundOriginAllowlistJson()),
                t.getArchivedAt(),
                t.getCreatedAt());
    }

    private String writeStringList(List<String> list) {
        try {
            return json.writeValueAsString(list == null ? List.of() : list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("workspace: failed to encode allowlist", e);
        }
    }

    private List<String> readStringList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return json.readValue(raw, STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    /**
     * Workspace summary shape. {@code id} and {@code code} happen to be the
     * same string today (since {@code tenants.id} is the slug) — V24 will
     * split them; the field is here now so the UI contract is stable.
     *
     * <p>{@code hostBaseUrl}, {@code hostBaseUrlAllowlist} are OUTBOUND —
     * resolver inputs for HTTP-tool dispatch.
     * {@code inboundOriginAllowlist} is INBOUND — origins allowed to hit
     * {@code /v1/sessions/*}. All three are surfaced on the General tab
     * so the operator can see the live values without a second call.</p>
     */
    public record WorkspaceSummary(
            String id,
            String code,
            String displayName,
            String status,
            String hostBaseUrl,
            List<String> hostBaseUrlAllowlist,
            List<String> inboundOriginAllowlist,
            OffsetDateTime archivedAt,
            OffsetDateTime createdAt
    ) {}

    /** Listing item: the workspace plus the caller's role in it. */
    public record MyWorkspace(WorkspaceSummary workspace, Role role) {}
}
