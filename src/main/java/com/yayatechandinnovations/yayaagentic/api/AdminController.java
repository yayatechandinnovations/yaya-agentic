package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.auth.Authorizer;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.persistence.*;
import com.yayatechandinnovations.yayaagentic.tenant.BaseUrlValidator;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import com.yayatechandinnovations.yayaagentic.tenant.migration.AbsoluteToPathMigrator;
import com.yayatechandinnovations.yayaagentic.tool.EntityBindingSpec;
import com.yayatechandinnovations.yayaagentic.tool.EntityCaptureSpec;
import com.yayatechandinnovations.yayaagentic.tool.HttpToolSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.util.*;
import java.util.stream.Collectors;

/**
 * M1 admin REST surface (§9). Versioned resources auto-bump on every POST.
 * Validation: profile capability + auth-binding references must resolve;
 * tool input/output schemas must parse; HTTP tool urlTemplate non-empty;
 * recording strategy scopeKind ∈ {TENANT, PROFILE}.
 *
 * <p>Operator authentication for the admin surface lands in M5 (F5.1);
 * for M1 these endpoints are open on the loopback the Flutter app uses.</p>
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};
    private static final TypeReference<List<EntityCaptureSpec>> CAPTURE_LIST_REF = new TypeReference<>() {};
    private static final TypeReference<List<EntityBindingSpec>> BINDING_LIST_REF = new TypeReference<>() {};

    /**
     * Names that almost certainly carry secrets, tokens, or credentials. When
     * an operator declares an {@link EntityCaptureSpec} whose {@code name}
     * matches this pattern with {@code visibleToLlm=true}, we log a WARN —
     * the operator has explicitly opted in, but it's a high-cost mistake to
     * make silently. See entity-memory-design §13 ("Admin UI") and §12 row 9.
     */
    private static final java.util.regex.Pattern SENSITIVE_ENTITY_NAME =
            java.util.regex.Pattern.compile(
                    "(?i).*(token|secret|credential|password|api[_-]?key).*");

    private final TenantGuard tenantGuard;
    private final ProfileRepository profiles;
    private final CapabilityRepository capabilities;
    private final ToolRepository tools;
    private final AuthBindingRepository authBindings;
    private final RecordingStrategyRepository recordingStrategies;
    private final AuditAuthzRepository auditAuthz;
    private final List<Authenticator> authenticators;
    private final List<Authorizer> authorizers;
    private final ObjectMapper json;
    private final com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog runtimeCatalog;
    private final com.yayatechandinnovations.yayaagentic.engine.bootstrap.CatalogMapper catalogMapper;
    private final AbsoluteToPathMigrator absoluteToPathMigrator;
    private final com.yayatechandinnovations.yayaagentic.tool.ToolNameRepairService toolNameRepair;
    private final WorkspaceAccessHelper workspaceAccess;

    public AdminController(TenantGuard tenantGuard,
                           ProfileRepository profiles,
                           CapabilityRepository capabilities,
                           ToolRepository tools,
                           AuthBindingRepository authBindings,
                           RecordingStrategyRepository recordingStrategies,
                           AuditAuthzRepository auditAuthz,
                           List<Authenticator> authenticators,
                           List<Authorizer> authorizers,
                           ObjectMapper json,
                           com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog runtimeCatalog,
                           com.yayatechandinnovations.yayaagentic.engine.bootstrap.CatalogMapper catalogMapper,
                           AbsoluteToPathMigrator absoluteToPathMigrator,
                           com.yayatechandinnovations.yayaagentic.tool.ToolNameRepairService toolNameRepair,
                           WorkspaceAccessHelper workspaceAccess) {
        this.tenantGuard = tenantGuard;
        this.profiles = profiles;
        this.capabilities = capabilities;
        this.tools = tools;
        this.authBindings = authBindings;
        this.recordingStrategies = recordingStrategies;
        this.auditAuthz = auditAuthz;
        this.authenticators = authenticators;
        this.authorizers = authorizers;
        this.json = json;
        this.runtimeCatalog = runtimeCatalog;
        this.catalogMapper = catalogMapper;
        this.absoluteToPathMigrator = absoluteToPathMigrator;
        this.toolNameRepair = toolNameRepair;
        this.workspaceAccess = workspaceAccess;
    }

    // ---- Profiles -------------------------------------------------------

    @PostMapping("/profiles")
    @Transactional
    public ResponseEntity<AdminDtos.ProfileResponse> createProfile(@RequestBody AdminDtos.ProfileRequest req,
                                                                    ServerWebExchange exchange) {
        String tenant = requireWorkspaceAccess(req.tenant(), exchange, Role.MEMBER);
        require(req.id(), "profile.id");
        require(req.displayName(), "profile.displayName");
        require(req.introOneLiner(), "profile.introOneLiner");
        require(req.systemPromptFragment(), "profile.systemPromptFragment");

        tenantGuard.requireWritable(tenant);
        for (String capId : safeList(req.capabilities())) {
            if (capabilities.findByTenantIdAndIdOrderByVersionDesc(tenant, capId).isEmpty()) {
                throw badRequest("profile references unknown capability: " + capId);
            }
        }
        if (req.authBindingId() != null && !req.authBindingId().isBlank()
                && !authBindings.existsById(new AuthBindingEntity.PK(tenant, req.authBindingId()))) {
            throw badRequest("profile references unknown auth binding: " + req.authBindingId());
        }

        int nextVersion = profiles.findByTenantIdAndIdOrderByVersionDesc(tenant, req.id())
                .stream().mapToInt(ProfileEntity::getVersion).max().orElse(0) + 1;

        ProfileEntity entity = new ProfileEntity(tenant, req.id(), nextVersion,
                req.displayName(), req.introOneLiner(), req.systemPromptFragment());
        entity.setCapabilitiesJson(writeJson(safeList(req.capabilities())));
        entity.setMetadataJson(writeJson(req.metadata() == null ? Map.of() : req.metadata()));
        entity.setExtensionsJson(req.extensions() == null || req.extensions().isEmpty()
                ? null : writeJson(req.extensions()));
        entity.setAuthBindingId(req.authBindingId());
        entity.setLanguage(req.language());
        entity.setDefaultStartFlowId(
                req.defaultStartFlowId() == null || req.defaultStartFlowId().isBlank()
                        ? null : req.defaultStartFlowId().trim());
        profiles.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(toProfileResponse(entity));
    }

    @GetMapping("/profiles")
    public List<AdminDtos.ProfileResponse> listProfiles(@RequestParam(required = false) String tenant,
                                                        ServerWebExchange exchange) {
        String resolved = requireWorkspaceAccess(tenant, exchange, Role.VIEWER);
        return profiles.findByTenantId(resolved).stream().map(this::toProfileResponse).toList();
    }

    // ---- Capabilities ---------------------------------------------------

    @PostMapping("/capabilities")
    @Transactional
    public ResponseEntity<AdminDtos.CapabilityResponse> createCapability(
            @RequestBody AdminDtos.CapabilityRequest req,
            ServerWebExchange exchange) {
        String tenant = requireWorkspaceAccess(req.tenant(), exchange, Role.MEMBER);
        require(req.id(), "capability.id");
        require(req.label(), "capability.label");
        tenantGuard.requireWritable(tenant);
        for (String toolId : safeList(req.tools())) {
            if (tools.findByTenantIdAndIdOrderByVersionDesc(tenant, toolId).isEmpty()) {
                throw badRequest("capability references unknown tool: " + toolId);
            }
        }
        int nextVersion = capabilities.findByTenantIdAndIdOrderByVersionDesc(tenant, req.id())
                .stream().mapToInt(CapabilityEntity::getVersion).max().orElse(0) + 1;

        CapabilityEntity entity = new CapabilityEntity(tenant, req.id(), nextVersion, req.label());
        entity.setDescription(req.description());
        entity.setLlmGuidance(req.llmGuidance());
        entity.setToolIdsJson(writeJson(safeList(req.tools())));
        entity.setFollowUpHintsJson(writeJson(safeList(req.followUpHints())));
        if (req.menu() != null && !req.menu().isEmpty()) {
            // Validate by projecting into the runtime record (its compact
            // invariants produce the message the operator should see).
            try {
                json.convertValue(req.menu(),
                        com.yayatechandinnovations.yayaagentic.profile.MenuBlock.class);
            } catch (IllegalArgumentException ex) {
                throw badRequest("capability.menu is invalid: " + ex.getMessage());
            }
            entity.setMenuJson(writeJson(req.menu()));
        }
        capabilities.save(entity);
        runtimeCatalog.registerCapability(catalogMapper.toCapability(entity));
        return ResponseEntity.status(HttpStatus.CREATED).body(toCapabilityResponse(entity));
    }

    @GetMapping("/capabilities")
    public List<AdminDtos.CapabilityResponse> listCapabilities(
            @RequestParam(required = false) String tenant,
            ServerWebExchange exchange) {
        String resolved = requireWorkspaceAccess(tenant, exchange, Role.VIEWER);
        return capabilities.findAll().stream()
                .filter(c -> c.getTenantId().equals(resolved))
                .map(this::toCapabilityResponse).toList();
    }

    // ---- Tools ----------------------------------------------------------

    @PostMapping("/tools")
    @Transactional
    public ResponseEntity<AdminDtos.ToolResponse> createTool(@RequestBody AdminDtos.ToolRequest req,
                                                             ServerWebExchange exchange) {
        String tenant = requireWorkspaceAccess(req.tenant(), exchange, Role.MEMBER);
        require(req.id(), "tool.id");
        if (!com.yayatechandinnovations.yayaagentic.tool.ToolIdPattern.isValid(req.id())) {
            throw badRequest("tool.id must match " + com.yayatechandinnovations.yayaagentic.tool.ToolIdPattern.REGEX
                    + " (Anthropic tool-name constraint) — got '" + req.id() + "'");
        }
        require(req.inputSchemaJson(), "tool.inputSchemaJson");
        require(req.outputSchemaJson(), "tool.outputSchemaJson");
        if (req.handler() == null) throw badRequest("tool.handler is required");
        tenantGuard.requireWritable(tenant);
        parseOrFail(req.inputSchemaJson(), "tool.inputSchemaJson");
        parseOrFail(req.outputSchemaJson(), "tool.outputSchemaJson");

        AdminDtos.ToolHandlerDto h = req.handler();
        String kind = h.kind() == null ? "" : h.kind().toUpperCase(Locale.ROOT);
        ToolEntity entity = new ToolEntity(tenant, req.id(),
                nextToolVersion(tenant, req.id()),
                req.inputSchemaJson(), req.outputSchemaJson(), kind);
        entity.setRequiresJson(writeJson(req.requires() == null ? Map.of() : req.requires()));
        entity.setPolicyJson(writeJson(req.policy() == null ? Map.of() : req.policy()));

        // M2.10 — operator-declared captures + bindings. Validate by attempting
        // to project each wire spec into its runtime record (the records carry
        // strict invariants — non-blank, RFC-6901 pointer, kind/onConflict
        // pairing). Any IllegalArgumentException becomes a 400 with the same
        // message the operator will see in the Flutter form.
        List<EntityCaptureSpec> captures = req.captures() == null ? List.of() : req.captures();
        List<EntityBindingSpec> bindings = req.bindings() == null ? List.of() : req.bindings();
        validateCaptures(captures);
        validateBindings(bindings, req.inputSchemaJson());
        entity.setCapturesJson(writeJson(captures));
        entity.setBindingsJson(writeJson(bindings));
        entity.setSuggestionsJson(writeJson(req.suggestions() == null ? List.of() : req.suggestions()));

        switch (kind) {
            case "BEAN" -> {
                if (h.beanName() == null || h.beanName().isBlank()) {
                    throw badRequest("tool.handler.beanName is required for BEAN tools");
                }
                entity.setHandlerBeanName(h.beanName());
            }
            case "HTTP" -> {
                if (h.httpSpec() == null) {
                    throw badRequest("tool.handler.httpSpec is required for HTTP tools");
                }
                validateHttpSpec(h.httpSpec());
                entity.setHandlerHttpSpecJson(writeJson(h.httpSpec()));
                // §D4 — persist the declarative data-projection config alongside the
                // spec so it is queryable (the engine reads it back off the spec).
                AdminDtos.HttpResponseDto resp = h.httpSpec().response();
                if (resp != null) {
                    entity.setTableOutputsJson(resp.tableOutputs() == null ? null : writeJson(resp.tableOutputs()));
                    entity.setCardFieldsJson(resp.cardFields() == null ? null : writeJson(resp.cardFields()));
                }
            }
            default -> throw badRequest("tool.handler.kind must be BEAN or HTTP");
        }

        tools.save(entity);
        runtimeCatalog.registerTool(catalogMapper.toDescriptor(entity));
        return ResponseEntity.status(HttpStatus.CREATED).body(toToolResponse(entity));
    }

    @GetMapping("/tools")
    public List<AdminDtos.ToolResponse> listTools(@RequestParam(required = false) String tenant,
                                                  ServerWebExchange exchange) {
        String resolved = requireWorkspaceAccess(tenant, exchange, Role.VIEWER);
        return tools.findAll().stream()
                .filter(t -> t.getTenantId().equals(resolved))
                .map(this::toToolResponse).toList();
    }

    // ---- Auth bindings --------------------------------------------------

    @PostMapping("/auth-bindings")
    @Transactional
    public ResponseEntity<AdminDtos.AuthBindingResponse> createAuthBinding(
            @RequestBody AdminDtos.AuthBindingRequest req,
            ServerWebExchange exchange) {
        // Auth bindings carry secrets-adjacent semantics — gate at ADMIN+,
        // matching the JWT/OIDC issuer surface (see UI-UX §9.1).
        String tenant = requireWorkspaceAccess(req.tenant(), exchange, Role.ADMIN);
        require(req.id(), "authBinding.id");
        require(req.authenticatorRef(), "authBinding.authenticatorRef");
        tenantGuard.requireWritable(tenant);

        Set<String> known = authenticators.stream().map(Authenticator::name).collect(Collectors.toUnmodifiableSet());
        if (!known.contains(req.authenticatorRef())) {
            throw badRequest("authenticatorRef '" + req.authenticatorRef() + "' is not a registered Authenticator (known: " + known + ")");
        }

        AuthBindingEntity.PK pk = new AuthBindingEntity.PK(tenant, req.id());
        AuthBindingEntity entity = authBindings.findById(pk)
                .orElse(new AuthBindingEntity(tenant, req.id(), req.authenticatorRef()));
        entity.setAuthenticatorRef(req.authenticatorRef());
        entity.setAuthorizerChainJson(writeJson(safeList(req.authorizerChain())));
        authBindings.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(toAuthBindingResponse(entity));
    }

    @GetMapping("/auth-bindings")
    public List<AdminDtos.AuthBindingResponse> listAuthBindings(
            @RequestParam(required = false) String tenant,
            ServerWebExchange exchange) {
        // Bindings are secret-adjacent; require ADMIN to read.
        String resolved = requireWorkspaceAccess(tenant, exchange, Role.ADMIN);
        return authBindings.findAll().stream()
                .filter(b -> b.getTenantId().equals(resolved))
                .map(this::toAuthBindingResponse).toList();
    }

    @GetMapping("/auth/available")
    public AdminDtos.AuthAvailability listAvailableAuth() {
        List<String> authNames = authenticators.stream()
                .map(Authenticator::name)
                .filter(n -> !n.equals("chain"))     // hide the wrapper itself
                .sorted().toList();
        List<String> authorizerNames = authorizers.stream()
                .map(a -> a.getClass().getSimpleName())
                .filter(n -> !n.equals("AuthorizerChain"))
                .sorted().toList();
        return new AdminDtos.AuthAvailability(authNames, authorizerNames);
    }

    // ---- Recording strategies ------------------------------------------

    @PostMapping("/recording-strategies")
    @Transactional
    public ResponseEntity<AdminDtos.RecordingStrategyResponse> createRecordingStrategy(
            @RequestBody AdminDtos.RecordingStrategyRequest req,
            ServerWebExchange exchange) {
        String tenant = requireWorkspaceAccess(req.tenant(), exchange, Role.ADMIN);
        String scope = req.scopeKind() == null ? "" : req.scopeKind().toUpperCase(Locale.ROOT);
        if (!"TENANT".equals(scope) && !"PROFILE".equals(scope)) {
            throw badRequest("recordingStrategy.scopeKind must be TENANT or PROFILE");
        }
        require(req.scopeId(), "recordingStrategy.scopeId");
        if (req.strategy() == null) throw badRequest("recordingStrategy.strategy is required");

        Object kindObj = req.strategy().get("kind");
        if (!(kindObj instanceof String s)
                || !Set.of("single", "fanout", "tiered", "classified")
                        .contains(s.toLowerCase(Locale.ROOT))) {
            throw badRequest("strategy.kind must be one of single|fanout|tiered|classified");
        }
        tenantGuard.requireWritable(tenant);

        int nextVersion = recordingStrategies
                .findByTenantIdAndScopeKindAndScopeIdOrderByVersionDesc(tenant, scope, req.scopeId())
                .stream().mapToInt(RecordingStrategyEntity::getVersion).max().orElse(0) + 1;

        RecordingStrategyEntity entity = new RecordingStrategyEntity(
                tenant, scope, req.scopeId(), writeJson(req.strategy()), nextVersion);
        recordingStrategies.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(toStrategyResponse(entity));
    }

    @GetMapping("/recording-strategies/{scopeKind}/{scopeId}")
    public AdminDtos.RecordingStrategyResponse getRecordingStrategy(
            @RequestParam(required = false) String tenant,
            @PathVariable String scopeKind,
            @PathVariable String scopeId,
            ServerWebExchange exchange) {
        String resolved = requireWorkspaceAccess(tenant, exchange, Role.VIEWER);
        return recordingStrategies
                .findLatest(resolved, scopeKind.toUpperCase(Locale.ROOT), scopeId)
                .map(this::toStrategyResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no recording strategy at " + scopeKind + "/" + scopeId));
    }

    // ---- Audit /authz ---------------------------------------------------

    @GetMapping("/audit/authz")
    public AdminDtos.AuthzAuditPage searchAuthzAudit(
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String principal,
            @RequestParam(name = "toolId", required = false) String toolId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            ServerWebExchange exchange) {
        // Audit reads require ADMIN — UI-UX §9.1.
        String resolved = requireWorkspaceAccess(tenant, exchange, Role.ADMIN);
        var pr = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(pageSize, 200)));
        var result = auditAuthz.search(resolved,
                decision == null ? null : decision.toUpperCase(Locale.ROOT),
                principal, toolId, pr);
        List<AdminDtos.AuthzAuditEntry> items = result.getContent().stream()
                .map(this::toAuditEntry).toList();
        return new AdminDtos.AuthzAuditPage(items, result.getNumber(), result.getSize(), result.getTotalElements());
    }

    // ---- Migration helper (§9.2) ---------------------------------------

    @PostMapping("/tools/migrate-to-path")
    public AbsoluteToPathMigrator.Plan migrateToPath(@RequestParam("tenant") String tenant,
                                                     @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun,
                                                     ServerWebExchange exchange) {
        String resolved = requireWorkspaceAccess(tenant, exchange, Role.ADMIN);
        tenantGuard.requireWritable(resolved);
        return dryRun
                ? absoluteToPathMigrator.plan(resolved)
                : absoluteToPathMigrator.apply(resolved);
    }

    /**
     * Repair legacy tool ids that don't satisfy the Anthropic tool-name
     * constraint. Sanitises every offender ({@code [^a-zA-Z0-9_-]+ -> _},
     * collapse repeats, truncate to 128), cascades the rename to every
     * capability that referenced the old id, and re-syncs the in-memory
     * runtime catalog so subsequent LLM calls send the new names.
     *
     * <p>Historical {@code session_turns} and {@code audit_authz} rows are
     * NOT rewritten — they record what actually happened at the time.
     *
     * <p>Fails with 409 + a collision map if any sanitised target would
     * clash with another existing tool id; nothing is written in that case.
     */
    @PostMapping("/tools/repair-names")
    public ResponseEntity<?> repairToolNames(@RequestParam("tenant") String tenant,
                                             @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun,
                                             ServerWebExchange exchange) {
        String resolved = requireWorkspaceAccess(tenant, exchange, Role.ADMIN);
        tenantGuard.requireWritable(resolved);
        if (dryRun) {
            return ResponseEntity.ok(toolNameRepair.plan(resolved));
        }
        try {
            var report = toolNameRepair.apply(resolved);
            refreshRuntimeCatalogAfterRepair(resolved, report);
            return ResponseEntity.ok(report);
        } catch (com.yayatechandinnovations.yayaagentic.tool.ToolNameRepairService.CollisionException ce) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "tool_id_repair_collision",
                    "message", "sanitised tool ids would collide; rename one side manually then retry",
                    "collisions", ce.collisions()));
        }
    }

    /**
     * Re-register every renamed tool and every capability whose tool list
     * may have changed. The orphaned old-id entries in {@code M0Catalog}
     * are harmless (no capability references them) but get garbage-collected
     * on next process restart when {@code RuntimeCatalogLoader} hydrates
     * from the DB.
     */
    private void refreshRuntimeCatalogAfterRepair(String tenant,
            com.yayatechandinnovations.yayaagentic.tool.ToolNameRepairService.Report report) {
        if (report == null || report.renames().isEmpty()) return;
        java.util.Set<String> renamed = new java.util.HashSet<>();
        for (var r : report.renames()) renamed.add(r.to());
        for (ToolEntity t : tools.findByTenantId(tenant)) {
            if (renamed.contains(t.getId())) {
                runtimeCatalog.registerTool(catalogMapper.toDescriptor(t));
            }
        }
        for (CapabilityEntity c : capabilities.findByTenantId(tenant)) {
            runtimeCatalog.registerCapability(catalogMapper.toCapability(c));
        }
    }

    // ---- Validation + JSON helpers --------------------------------------

    private void validateHttpSpec(AdminDtos.HttpHandlerDto spec) {
        require(spec.method(), "httpSpec.method");
        // Path-only enforcement per tenant-registry-design §6.1. The host
        // resolves from the tool's tenant at dispatch time; absolute URLs
        // re-introduce the per-tenant fan-out / cloning bug we just closed.
        BaseUrlValidator.validatePathOnlyTemplate(spec.urlTemplate());
        try {
            HttpToolSpec.HttpMethod.valueOf(spec.method().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest("httpSpec.method '" + spec.method() + "' is not a valid HTTP method");
        }
        if (spec.authForwarding() != null) {
            try {
                HttpToolSpec.AuthForwarding.valueOf(spec.authForwarding().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw badRequest("httpSpec.authForwarding must be NONE | PRINCIPAL_TOKEN | SERVICE_TOKEN");
            }
        }
    }

    /**
     * Validates each capture by projecting it to its runtime record (which
     * carries the invariants — non-blank fields, RFC-6901 pointer, valid
     * kind/onConflict pairing). The wire-form enum strings must also be
     * known {@link com.yayatechandinnovations.yayaagentic.core.entity.EntityScope}
     * / {@link com.yayatechandinnovations.yayaagentic.core.entity.EntityKind}
     * / {@link com.yayatechandinnovations.yayaagentic.tool.OnConflict} names —
     * an unknown value throws an {@link IllegalArgumentException} from
     * {@code Enum.valueOf} which we surface as 400 with the original message.
     *
     * <p>Sensitive-name lint: when an entity's {@code name} matches the
     * secret/token/credential pattern AND {@code visibleToLlm=true}, we
     * accept the value (operators sometimes have legitimate cases) but log
     * a WARN — explicit opt-in to a high-cost mistake should be noisy.
     */
    private void validateCaptures(List<EntityCaptureSpec> captures) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < captures.size(); i++) {
            EntityCaptureSpec c = captures.get(i);
            if (c == null) {
                throw badRequest("tool.captures[" + i + "] is null");
            }
            try {
                c.toRuntime();
            } catch (IllegalArgumentException ex) {
                throw badRequest("tool.captures[" + i + "]: " + ex.getMessage());
            }
            if (!names.add(c.name())) {
                throw badRequest("tool.captures[" + i + "] duplicate entity name '" + c.name()
                        + "' — a single tool cannot capture the same entity twice");
            }
            if (c.visibleToLlm() && SENSITIVE_ENTITY_NAME.matcher(c.name()).matches()) {
                log.warn("admin: capture name '{}' looks sensitive but visibleToLlm=true — "
                        + "the LLM will see this name in every prompt. Confirm this is intentional.",
                        c.name());
            }
        }
    }

    /**
     * Validates each binding by projecting it to its runtime record and
     * (optionally) confirming the {@code argName} is a property of the tool's
     * input schema. The latter is a soft check — if the schema doesn't parse
     * as an object schema we accept the binding rather than failing the
     * save; the engine will surface a runtime error if the arg never resolves.
     */
    private void validateBindings(List<EntityBindingSpec> bindings, String inputSchemaJson) {
        java.util.Set<String> declaredProps = inputSchemaProperties(inputSchemaJson);
        java.util.Set<String> argNames = new java.util.HashSet<>();
        for (int i = 0; i < bindings.size(); i++) {
            EntityBindingSpec b = bindings.get(i);
            if (b == null) {
                throw badRequest("tool.bindings[" + i + "] is null");
            }
            try {
                b.toRuntime();
            } catch (IllegalArgumentException ex) {
                throw badRequest("tool.bindings[" + i + "]: " + ex.getMessage());
            }
            if (!argNames.add(b.argName())) {
                throw badRequest("tool.bindings[" + i + "] duplicate argName '" + b.argName()
                        + "' — a single tool cannot bind the same arg twice");
            }
            if (!declaredProps.isEmpty() && !declaredProps.contains(b.argName())) {
                throw badRequest("tool.bindings[" + i + "] argName '" + b.argName()
                        + "' is not a property in this tool's inputSchema (known: "
                        + declaredProps + ")");
            }
        }
    }

    /**
     * Best-effort extraction of {@code properties} keys from a JSON-Schema
     * object schema. Returns an empty set when the schema isn't an object
     * with a {@code properties} map — in which case binding validation
     * skips the membership check.
     */
    private java.util.Set<String> inputSchemaProperties(String inputSchemaJson) {
        if (inputSchemaJson == null || inputSchemaJson.isBlank()) return java.util.Set.of();
        try {
            com.fasterxml.jackson.databind.JsonNode root = json.readTree(inputSchemaJson);
            com.fasterxml.jackson.databind.JsonNode props = root == null ? null : root.get("properties");
            if (props == null || !props.isObject()) return java.util.Set.of();
            java.util.Set<String> out = new java.util.HashSet<>();
            props.fieldNames().forEachRemaining(out::add);
            return out;
        } catch (Exception e) {
            return java.util.Set.of();
        }
    }

    private void parseOrFail(String json, String field) {
        try {
            this.json.readTree(json);
        } catch (Exception e) {
            throw badRequest(field + " is not valid JSON: " + e.getMessage());
        }
    }

    private int nextToolVersion(String tenant, String id) {
        return tools.findByTenantIdAndIdOrderByVersionDesc(tenant, id)
                .stream().mapToInt(ToolEntity::getVersion).max().orElse(0) + 1;
    }

    // ---- Mapping back to DTOs ------------------------------------------

    private AdminDtos.ProfileResponse toProfileResponse(ProfileEntity e) {
        return new AdminDtos.ProfileResponse(
                e.getId(), e.getVersion(), e.getTenantId(),
                e.getDisplayName(), e.getIntro(), e.getSystemPrompt(),
                readJson(e.getCapabilitiesJson(), new TypeReference<List<String>>() {}),
                e.getAuthBindingId(),
                e.getLanguage(),
                readJson(e.getMetadataJson(), MAP_REF),
                e.getStatus(), e.getCreatedAt(),
                e.getExtensionsJson() == null ? null : readJson(e.getExtensionsJson(), MAP_REF),
                e.getDefaultStartFlowId());
    }

    private AdminDtos.CapabilityResponse toCapabilityResponse(CapabilityEntity e) {
        return new AdminDtos.CapabilityResponse(
                e.getId(), e.getVersion(), e.getTenantId(),
                e.getLabel(), e.getDescription(), e.getLlmGuidance(),
                readJson(e.getToolIdsJson(), new TypeReference<List<String>>() {}),
                readJson(e.getFollowUpHintsJson(), new TypeReference<List<String>>() {}),
                e.getMenuJson() == null ? null
                        : readJson(e.getMenuJson(), new TypeReference<Map<String, Object>>() {}),
                null /* createdAt populated by DB; not loaded here in M1 */);
    }

    private AdminDtos.ToolResponse toToolResponse(ToolEntity e) {
        AdminDtos.ToolHandlerDto handler = "BEAN".equals(e.getHandlerKind())
                ? new AdminDtos.ToolHandlerDto("BEAN", e.getHandlerBeanName(), null)
                : new AdminDtos.ToolHandlerDto("HTTP", null,
                        readJson(e.getHandlerHttpSpecJson(), new TypeReference<>() {}));
        return new AdminDtos.ToolResponse(
                e.getId(), e.getVersion(), e.getTenantId(),
                e.getInputSchemaJson(), e.getOutputSchemaJson(),
                readJson(e.getRequiresJson(), MAP_REF),
                handler,
                readJson(e.getPolicyJson(), MAP_REF),
                readList(e.getCapturesJson(), CAPTURE_LIST_REF),
                readList(e.getBindingsJson(), BINDING_LIST_REF),
                readList(e.getSuggestionsJson(),
                        new TypeReference<List<com.yayatechandinnovations.yayaagentic.tool.SuggestionRule>>() {}),
                e.getStatus(), null);
    }

    private AdminDtos.AuthBindingResponse toAuthBindingResponse(AuthBindingEntity e) {
        return new AdminDtos.AuthBindingResponse(
                e.getId(), e.getTenantId(), e.getAuthenticatorRef(),
                readJson(e.getAuthorizerChainJson(), new TypeReference<List<String>>() {}),
                e.getCreatedAt());
    }

    private AdminDtos.RecordingStrategyResponse toStrategyResponse(RecordingStrategyEntity e) {
        return new AdminDtos.RecordingStrategyResponse(
                e.getTenantId(), e.getScopeKind(), e.getScopeId(),
                readJson(e.getStrategyJson(), MAP_REF),
                e.getVersion(), e.getCreatedAt());
    }

    private AdminDtos.AuthzAuditEntry toAuditEntry(AuditAuthzEntity e) {
        return new AdminDtos.AuthzAuditEntry(
                e.getId(), e.getTenantId(),
                e.getSessionId() == null ? null : e.getSessionId().toString(),
                e.getTurnId() == null ? null : e.getTurnId().toString(),
                e.getPrincipalSubject(), e.getToolId(),
                e.getDecision(), e.getUserReason(), e.getAuditReason(),
                readJson(e.getPolicyTraceJson(), MAP_REF),
                e.getCreatedAt());
    }

    // ---- Tiny utilities -------------------------------------------------

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("admin: failed to encode JSON", ex);
        }
    }

    private <T> T readJson(String raw, TypeReference<T> type) {
        if (raw == null || raw.isBlank()) return json.convertValue(Map.of(), type);
        try {
            return json.readValue(raw, type);
        } catch (Exception e) {
            return json.convertValue(Map.of(), type);
        }
    }

    /**
     * Like {@link #readJson} but for list-typed columns: defaults to an empty
     * list on missing/unreadable input instead of an empty map. Avoids the
     * {@code convertValue(Map.of(), List<X>)} ClassCastException we'd hit if
     * we used the generic helper for list types.
     */
    private <T> List<T> readList(String raw, TypeReference<List<T>> type) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<T> parsed = json.readValue(raw, type);
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Thin pass-through to the shared helper — kept so call-sites read consistently. */
    private String requireWorkspaceAccess(String tenant, ServerWebExchange exchange, Role minRole) {
        return workspaceAccess.require(tenant, exchange, minRole);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw badRequest(field + " is required");
    }

    private static <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
