package com.yayatechandinnovations.yayaagentic.engine.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.persistence.CapabilityEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ToolEntity;
import com.yayatechandinnovations.yayaagentic.profile.Capability;
import com.yayatechandinnovations.yayaagentic.tool.HttpToolSpec;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Single place that knows how to turn {@link ToolEntity} / {@link CapabilityEntity}
 * rows from Postgres into the runtime records the engine consults via
 * {@link M0Catalog}. Used at startup by {@code RuntimeCatalogLoader} and
 * on every successful admin POST by {@code AdminController}.
 */
@Component
public class CatalogMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<PermissionRequirement> PERMISSION_REQUIREMENT = new TypeReference<>() {};
    private static final TypeReference<HttpToolSpec> HTTP_TOOL_SPEC = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> STRING_MAP = new TypeReference<>() {};

    private final ObjectMapper json;

    public CatalogMapper(ObjectMapper json) {
        this.json = json;
    }

    public ToolDescriptor toDescriptor(ToolEntity e) {
        Ids.ToolId id = new Ids.ToolId(e.getId());
        PermissionRequirement requires = readJson(e.getRequiresJson(), PERMISSION_REQUIREMENT, PermissionRequirement.none());

        ToolHandlerRef handler = "BEAN".equals(e.getHandlerKind())
                ? new ToolHandlerRef.Bean(e.getHandlerBeanName())
                : new ToolHandlerRef.Http(readJson(e.getHandlerHttpSpecJson(), HTTP_TOOL_SPEC, null));

        ToolPolicy policy = readPolicy(e.getPolicyJson());

        return new ToolDescriptor(id,
                e.getInputSchemaJson(),
                e.getOutputSchemaJson(),
                requires == null ? PermissionRequirement.none() : requires,
                handler,
                policy);
    }

    public Capability toCapability(CapabilityEntity e) {
        return new Capability(
                new Ids.CapabilityId(e.getId()),
                e.getLabel(),
                e.getDescription(),
                e.getLlmGuidance(),
                readJson(e.getToolIdsJson(), STRING_LIST, List.of()).stream()
                        .map(Ids.ToolId::new)
                        .toList(),
                readJson(e.getFollowUpHintsJson(), STRING_LIST, List.of()),
                readJson(e.getRequiresJson(), PERMISSION_REQUIREMENT, PermissionRequirement.none()));
    }

    // ---- internals ------------------------------------------------------

    private ToolPolicy readPolicy(String policyJson) {
        if (policyJson == null || policyJson.isBlank() || "{}".equals(policyJson.trim())) {
            return ToolPolicy.defaults();
        }
        Map<String, Object> raw = readJson(policyJson, STRING_MAP, Map.of());
        Duration timeout = parseDuration(raw.get("timeout"), Duration.ofSeconds(10));
        int maxRetries = toInt(raw.get("maxRetries"), 0);
        boolean idempotent = toBool(raw.get("idempotent"), true);
        boolean confirmable = toBool(raw.get("confirmable"), false);
        return new ToolPolicy(timeout, maxRetries, idempotent, confirmable);
    }

    private <T> T readJson(String raw, TypeReference<T> type, T fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            T parsed = json.readValue(raw, type);
            return parsed == null ? fallback : parsed;
        } catch (JsonProcessingException ex) {
            return fallback;
        }
    }

    private static Duration parseDuration(Object value, Duration fallback) {
        if (value == null) return fallback;
        try {
            return Duration.parse(Objects.toString(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
        }
        return fallback;
    }

    private static boolean toBool(Object value, boolean fallback) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s);
        return fallback;
    }
}
