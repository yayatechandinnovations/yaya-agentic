package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.tool.EntityBindingSpec;
import com.yayatechandinnovations.yayaagentic.tool.EntityCaptureSpec;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Request / response shapes for the M1 admin REST surface (§9).
 * Kept in one file because each is tiny and they're co-evolved with the
 * controller endpoints.
 */
public final class AdminDtos {

    private AdminDtos() {}

    // ---- Profiles -------------------------------------------------------

    public record ProfileRequest(
            String tenant,
            String id,
            String displayName,
            String introOneLiner,
            String systemPromptFragment,
            List<String> capabilities,
            String authBindingId,
            String language,
            Map<String, Object> metadata,
            /** App-specific traits keyed by namespaced kind → opaque payload
             *  (e.g. a {@code "sales.store"} kind whose payload yaya-sales defines).
             *  The framework stores/echoes these but never interprets them. */
            Map<String, Object> extensions,
            /** Nullable id of the Session Start Flow to run by default for this profile. */
            String defaultStartFlowId
    ) {
        /** Back-compat: a profile with no extensions. */
        public ProfileRequest(String tenant, String id, String displayName, String introOneLiner,
                              String systemPromptFragment, List<String> capabilities,
                              String authBindingId, String language, Map<String, Object> metadata) {
            this(tenant, id, displayName, introOneLiner, systemPromptFragment, capabilities,
                    authBindingId, language, metadata, null, null);
        }

        /** Back-compat: extensions but no default start flow. */
        public ProfileRequest(String tenant, String id, String displayName, String introOneLiner,
                              String systemPromptFragment, List<String> capabilities,
                              String authBindingId, String language, Map<String, Object> metadata,
                              Map<String, Object> extensions) {
            this(tenant, id, displayName, introOneLiner, systemPromptFragment, capabilities,
                    authBindingId, language, metadata, extensions, null);
        }
    }

    public record ProfileResponse(
            String id, int version, String tenant, String displayName,
            String introOneLiner, String systemPromptFragment,
            List<String> capabilities, String authBindingId,
            String language,
            Map<String, Object> metadata, String status, OffsetDateTime createdAt,
            Map<String, Object> extensions, String defaultStartFlowId
    ) {}

    // ---- Capabilities ---------------------------------------------------

    public record CapabilityRequest(
            String tenant, String id, String label, String description,
            String llmGuidance, List<String> tools, List<String> followUpHints
    ) {}

    public record CapabilityResponse(
            String id, int version, String tenant, String label,
            String description, String llmGuidance, List<String> tools,
            List<String> followUpHints,
            OffsetDateTime createdAt
    ) {}

    // ---- Tools ----------------------------------------------------------

    public record ToolRequest(
            String tenant, String id, String inputSchemaJson, String outputSchemaJson,
            Map<String, Object> requires, ToolHandlerDto handler,
            Map<String, Object> policy,
            List<EntityCaptureSpec> captures,
            List<EntityBindingSpec> bindings
    ) {
        public ToolRequest {
            captures = captures == null ? List.of() : List.copyOf(captures);
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
        }

        /** Compat ctor for tools that don't declare entity-memory hooks. */
        public ToolRequest(String tenant, String id, String inputSchemaJson, String outputSchemaJson,
                           Map<String, Object> requires, ToolHandlerDto handler,
                           Map<String, Object> policy) {
            this(tenant, id, inputSchemaJson, outputSchemaJson, requires, handler, policy,
                    List.of(), List.of());
        }
    }

    public record ToolHandlerDto(
            String kind,                          // BEAN | HTTP
            String beanName,                      // when kind=BEAN
            HttpHandlerDto httpSpec               // when kind=HTTP
    ) {}

    public record HttpHandlerDto(
            String method, String urlTemplate,
            Map<String, String> headerTemplates,
            HttpBodyDto body,
            HttpResponseDto response,
            String authForwarding                 // NONE | PRINCIPAL_TOKEN | SERVICE_TOKEN
    ) {}

    public record HttpBodyDto(String contentType, String template) {}

    public record HttpResponseDto(String jsonPath, Map<String, String> headerOutputs) {}

    public record ToolResponse(
            String id, int version, String tenant,
            String inputSchemaJson, String outputSchemaJson,
            Map<String, Object> requires, ToolHandlerDto handler,
            Map<String, Object> policy,
            List<EntityCaptureSpec> captures,
            List<EntityBindingSpec> bindings,
            String status,
            OffsetDateTime createdAt
    ) {
        public ToolResponse {
            captures = captures == null ? List.of() : List.copyOf(captures);
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
        }
    }

    // ---- Auth bindings --------------------------------------------------

    public record AuthBindingRequest(
            String tenant, String id, String authenticatorRef,
            List<String> authorizerChain
    ) {}

    public record AuthBindingResponse(
            String id, String tenant, String authenticatorRef,
            List<String> authorizerChain, OffsetDateTime createdAt
    ) {}

    public record AuthAvailability(List<String> authenticators, List<String> authorizers) {}

    // ---- Recording strategies -------------------------------------------

    public record RecordingStrategyRequest(
            String tenant,
            String scopeKind,                     // TENANT | PROFILE
            String scopeId,
            Map<String, Object> strategy          // kind + primary + sinks + ...
    ) {}

    public record RecordingStrategyResponse(
            String tenant, String scopeKind, String scopeId,
            Map<String, Object> strategy, int version, OffsetDateTime createdAt
    ) {}

    // ---- Audit ----------------------------------------------------------

    public record AuthzAuditEntry(
            Long id, String tenant, String sessionId, String turnId,
            String principal, String toolId,
            String decision, String userReason, String auditReason,
            Map<String, Object> policyTrace, OffsetDateTime createdAt
    ) {}

    public record AuthzAuditPage(List<AuthzAuditEntry> items, int page, int pageSize, long total) {}

    // ---- Tenants --------------------------------------------------------

    public record TenantRequest(
            String id,                            // ignored on PUT/PATCH
            String displayName,
            String hostBaseUrl,
            List<String> hostBaseUrlAllowlist,
            List<String> inboundOriginAllowlist,
            Boolean requireHttps,
            String defaultAuthenticatorBindingId,
            Long defaultRecordingStrategyId,
            Map<String, Object> settings
    ) {}

    public record TenantResponse(
            String id,
            String displayName,
            String status,
            String hostBaseUrl,
            List<String> hostBaseUrlAllowlist,
            List<String> inboundOriginAllowlist,
            Boolean requireHttps,
            String defaultAuthenticatorBindingId,
            Long defaultRecordingStrategyId,
            Map<String, Object> settings,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            OffsetDateTime archivedAt,
            String createdBy
    ) {}

    public record TenantHealthResponse(
            String tenantId,
            String status,
            boolean hostBaseUrlSet,
            boolean authBindingResolves,
            boolean recordingStrategyResolves,
            int dependencyCount,
            List<String> warnings
    ) {}

    // ---- Generic --------------------------------------------------------

    public record ApiError(String error, String message) {}
}
