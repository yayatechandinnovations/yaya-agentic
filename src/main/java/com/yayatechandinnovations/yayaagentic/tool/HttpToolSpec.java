package com.yayatechandinnovations.yayaagentic.tool;

import java.util.Map;

/**
 * Declarative description of a remote HTTP tool. Args → request via
 * {@code body} / URL template; response → tool result via {@code response}.
 * {@code authForwarding} is explicit: tokens are NEVER silently forwarded.
 *
 * <p>{@code urlTemplate} is a {@code String} (not {@code URI}) on purpose
 * — it carries {@code {placeholder}} segments that aren't valid in a URI
 * until the dispatcher substitutes args.</p>
 *
 * <p><b>Path-only invariant (tenant-registry-design §6.1).</b>
 * {@code urlTemplate} MUST be a path (e.g. {@code /v1/orders/{id}}). Absolute
 * URLs, protocol-relative {@code //…}, and non-http schemes are rejected at
 * save time. The host always resolves from the tool's tenant
 * {@code host_base_url} (optionally overridden by an allowlisted
 * {@code X-Yaya-Host-Base-Url}). This makes a tool descriptor portable across
 * tenants — cross-tenant cloning is a deterministic id-rewrite, no URL edits.</p>
 */
public record HttpToolSpec(
        HttpMethod method,
        String urlTemplate,
        Map<String, String> headerTemplates,
        BodyTemplate body,
        ResponseProjection response,
        AuthForwarding authForwarding
) {
    public enum HttpMethod { GET, POST, PUT, PATCH, DELETE }

    public enum AuthForwarding { NONE, PRINCIPAL_TOKEN, SERVICE_TOKEN }

    /** Builds a JSON body from input args. Concrete strategy chosen per spec. */
    public record BodyTemplate(String contentType, String template) {}

    /** Maps the HTTP response into the tool's declared output schema. */
    public record ResponseProjection(String jsonPath, Map<String, String> headerOutputs) {}
}
