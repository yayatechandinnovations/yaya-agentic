package com.yayatechandinnovations.yayaagentic.tool;

import java.util.List;
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

    /**
     * Maps the HTTP response into the tool's declared output schema.
     *
     * <p>{@code mediaOutputs} (optional, owner-cockpit design): when set,
     * an OK result names a tenant media asset the engine surfaces as a
     * signed-URL document attachment on the assistant turn. Keys:
     * {@code assetIdPath} (required, JSON path into the result, e.g.
     * {@code $.assetId}) and {@code filenamePath} (optional). The engine
     * verifies the asset belongs to the session's tenant before minting
     * the URL — a tool result can never link foreign media.</p>
     *
     * <p>{@code tableOutputs} / {@code cardFields} (optional, owner-cockpit v3
     * §D4): declarative projections the engine renders as {@code data_table} /
     * {@code data_card} UiHints. {@code tableOutputs} keys: {@code columnsPath}
     * + {@code rowsPath} (required, JSON paths into the result), {@code
     * titlePath} + {@code totalsPath} (optional). Each {@code cardFields} entry
     * maps {@code label} / {@code path} / {@code type} (type ∈ text, money,
     * count, percent, datetime). Config-driven, never LLM-driven.</p>
     */
    public record ResponseProjection(String jsonPath, Map<String, String> headerOutputs,
                                     Map<String, String> mediaOutputs,
                                     Map<String, String> tableOutputs,
                                     List<Map<String, String>> cardFields) {

        /** Compat constructor — most tools declare no media outputs. */
        public ResponseProjection(String jsonPath, Map<String, String> headerOutputs) {
            this(jsonPath, headerOutputs, null, null, null);
        }

        /** Compat constructor — media outputs but no table/card projections. */
        public ResponseProjection(String jsonPath, Map<String, String> headerOutputs,
                                  Map<String, String> mediaOutputs) {
            this(jsonPath, headerOutputs, mediaOutputs, null, null);
        }
    }
}
