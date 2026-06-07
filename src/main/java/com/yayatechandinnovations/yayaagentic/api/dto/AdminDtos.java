package com.yayatechandinnovations.yayaagentic.api.dto;

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
            Map<String, Object> metadata
    ) {}

    public record ProfileResponse(
            String id, int version, String tenant, String displayName,
            String introOneLiner, String systemPromptFragment,
            List<String> capabilities, String authBindingId,
            Map<String, Object> metadata, String status, OffsetDateTime createdAt
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
            Map<String, Object> policy
    ) {}

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
            Map<String, Object> policy, String status,
            OffsetDateTime createdAt
    ) {}

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

    // ---- Generic --------------------------------------------------------

    public record ApiError(String error, String message) {}
}
