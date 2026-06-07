package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.dto.AdminDtos;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.auth.Authorizer;
import com.yayatechandinnovations.yayaagentic.persistence.*;
import com.yayatechandinnovations.yayaagentic.tool.HttpToolSpec;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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

    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};

    private final TenantRepository tenants;
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

    public AdminController(TenantRepository tenants,
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
                           com.yayatechandinnovations.yayaagentic.engine.bootstrap.CatalogMapper catalogMapper) {
        this.tenants = tenants;
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
    }

    // ---- Profiles -------------------------------------------------------

    @PostMapping("/profiles")
    @Transactional
    public ResponseEntity<AdminDtos.ProfileResponse> createProfile(@RequestBody AdminDtos.ProfileRequest req) {
        String tenant = requireTenant(req.tenant());
        require(req.id(), "profile.id");
        require(req.displayName(), "profile.displayName");
        require(req.introOneLiner(), "profile.introOneLiner");
        require(req.systemPromptFragment(), "profile.systemPromptFragment");

        ensureTenant(tenant);
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
        entity.setAuthBindingId(req.authBindingId());
        entity.setLanguage(req.language());
        profiles.save(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(toProfileResponse(entity));
    }

    @GetMapping("/profiles")
    public List<AdminDtos.ProfileResponse> listProfiles(@RequestParam(defaultValue = "default") String tenant) {
        return profiles.findByTenantId(tenant).stream().map(this::toProfileResponse).toList();
    }

    // ---- Capabilities ---------------------------------------------------

    @PostMapping("/capabilities")
    @Transactional
    public ResponseEntity<AdminDtos.CapabilityResponse> createCapability(
            @RequestBody AdminDtos.CapabilityRequest req) {
        String tenant = requireTenant(req.tenant());
        require(req.id(), "capability.id");
        require(req.label(), "capability.label");
        ensureTenant(tenant);
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
        capabilities.save(entity);
        runtimeCatalog.registerCapability(catalogMapper.toCapability(entity));
        return ResponseEntity.status(HttpStatus.CREATED).body(toCapabilityResponse(entity));
    }

    @GetMapping("/capabilities")
    public List<AdminDtos.CapabilityResponse> listCapabilities(
            @RequestParam(defaultValue = "default") String tenant) {
        return capabilities.findAll().stream()
                .filter(c -> c.getTenantId().equals(tenant))
                .map(this::toCapabilityResponse).toList();
    }

    // ---- Tools ----------------------------------------------------------

    @PostMapping("/tools")
    @Transactional
    public ResponseEntity<AdminDtos.ToolResponse> createTool(@RequestBody AdminDtos.ToolRequest req) {
        String tenant = requireTenant(req.tenant());
        require(req.id(), "tool.id");
        require(req.inputSchemaJson(), "tool.inputSchemaJson");
        require(req.outputSchemaJson(), "tool.outputSchemaJson");
        if (req.handler() == null) throw badRequest("tool.handler is required");
        ensureTenant(tenant);
        parseOrFail(req.inputSchemaJson(), "tool.inputSchemaJson");
        parseOrFail(req.outputSchemaJson(), "tool.outputSchemaJson");

        AdminDtos.ToolHandlerDto h = req.handler();
        String kind = h.kind() == null ? "" : h.kind().toUpperCase(Locale.ROOT);
        ToolEntity entity = new ToolEntity(tenant, req.id(),
                nextToolVersion(tenant, req.id()),
                req.inputSchemaJson(), req.outputSchemaJson(), kind);
        entity.setRequiresJson(writeJson(req.requires() == null ? Map.of() : req.requires()));
        entity.setPolicyJson(writeJson(req.policy() == null ? Map.of() : req.policy()));

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
            }
            default -> throw badRequest("tool.handler.kind must be BEAN or HTTP");
        }

        tools.save(entity);
        runtimeCatalog.registerTool(catalogMapper.toDescriptor(entity));
        return ResponseEntity.status(HttpStatus.CREATED).body(toToolResponse(entity));
    }

    @GetMapping("/tools")
    public List<AdminDtos.ToolResponse> listTools(@RequestParam(defaultValue = "default") String tenant) {
        return tools.findAll().stream()
                .filter(t -> t.getTenantId().equals(tenant))
                .map(this::toToolResponse).toList();
    }

    // ---- Auth bindings --------------------------------------------------

    @PostMapping("/auth-bindings")
    @Transactional
    public ResponseEntity<AdminDtos.AuthBindingResponse> createAuthBinding(
            @RequestBody AdminDtos.AuthBindingRequest req) {
        String tenant = requireTenant(req.tenant());
        require(req.id(), "authBinding.id");
        require(req.authenticatorRef(), "authBinding.authenticatorRef");
        ensureTenant(tenant);

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
            @RequestParam(defaultValue = "default") String tenant) {
        return authBindings.findAll().stream()
                .filter(b -> b.getTenantId().equals(tenant))
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
            @RequestBody AdminDtos.RecordingStrategyRequest req) {
        String tenant = requireTenant(req.tenant());
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
        ensureTenant(tenant);

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
            @RequestParam(defaultValue = "default") String tenant,
            @PathVariable String scopeKind,
            @PathVariable String scopeId) {
        return recordingStrategies
                .findLatest(tenant, scopeKind.toUpperCase(Locale.ROOT), scopeId)
                .map(this::toStrategyResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no recording strategy at " + scopeKind + "/" + scopeId));
    }

    // ---- Audit /authz ---------------------------------------------------

    @GetMapping("/audit/authz")
    public AdminDtos.AuthzAuditPage searchAuthzAudit(
            @RequestParam(defaultValue = "default") String tenant,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String principal,
            @RequestParam(name = "toolId", required = false) String toolId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        var pr = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(pageSize, 200)));
        var result = auditAuthz.search(tenant,
                decision == null ? null : decision.toUpperCase(Locale.ROOT),
                principal, toolId, pr);
        List<AdminDtos.AuthzAuditEntry> items = result.getContent().stream()
                .map(this::toAuditEntry).toList();
        return new AdminDtos.AuthzAuditPage(items, result.getNumber(), result.getSize(), result.getTotalElements());
    }

    // ---- Validation + JSON helpers --------------------------------------

    private void validateHttpSpec(AdminDtos.HttpHandlerDto spec) {
        require(spec.method(), "httpSpec.method");
        require(spec.urlTemplate(), "httpSpec.urlTemplate");
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

    private void ensureTenant(String tenant) {
        if (!tenants.existsById(tenant)) {
            tenants.save(new TenantEntity(tenant, tenant));
        }
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
                e.getStatus(), e.getCreatedAt());
    }

    private AdminDtos.CapabilityResponse toCapabilityResponse(CapabilityEntity e) {
        return new AdminDtos.CapabilityResponse(
                e.getId(), e.getVersion(), e.getTenantId(),
                e.getLabel(), e.getDescription(), e.getLlmGuidance(),
                readJson(e.getToolIdsJson(), new TypeReference<List<String>>() {}),
                readJson(e.getFollowUpHintsJson(), new TypeReference<List<String>>() {}),
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

    private static String requireTenant(String tenant) {
        if (tenant == null || tenant.isBlank()) return "default";
        return tenant;
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
