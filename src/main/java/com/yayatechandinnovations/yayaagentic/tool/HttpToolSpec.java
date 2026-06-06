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
