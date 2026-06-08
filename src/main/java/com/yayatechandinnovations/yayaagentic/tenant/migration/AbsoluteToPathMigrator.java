package com.yayatechandinnovations.yayaagentic.tenant.migration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ToolEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ToolRepository;
import com.yayatechandinnovations.yayaagentic.tenant.BaseUrlValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One-shot helper for the M2.7 → M2.8 path-only tightening (§9.2). Walks a
 * tenant's HTTP tools, finds ones whose absolute {@code urlTemplate} origin
 * matches the tenant's {@code host_base_url}, and rewrites them to path form
 * with a version bump. Tools whose origin doesn't match end up in
 * {@code unsafe} with reason {@code ORIGIN_NOT_TENANT_HOST} — never silently
 * touched.
 */
@Service
public class AbsoluteToPathMigrator {

    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};

    private final ToolRepository tools;
    private final TenantRepository tenants;
    private final ObjectMapper json;

    public AbsoluteToPathMigrator(ToolRepository tools,
                                  TenantRepository tenants,
                                  ObjectMapper json) {
        this.tools = tools;
        this.tenants = tenants;
        this.json = json;
    }

    public record Candidate(String toolId, int version, String current, String rewritten) {}
    public record Unsafe(String toolId, int version, String current, String reason) {}
    public record Plan(List<Candidate> candidates, List<Unsafe> unsafe) {}

    public Plan plan(String tenantId) {
        TenantEntity tenant = tenants.findById(tenantId).orElseThrow(() ->
                AdminApiException.badRequest("unknown_tenant", "no such tenant: " + tenantId));
        String tenantOrigin = BaseUrlValidator.originOf(tenant.getHostBaseUrl());

        List<Candidate> candidates = new ArrayList<>();
        List<Unsafe> unsafe = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Walk LATEST version per (tenant, tool id) only — older versions are
        // historical; rewriting them creates confusing version trees.
        for (ToolEntity t : tools.findByTenantId(tenantId)) {
            if (!"HTTP".equals(t.getHandlerKind())) continue;
            String key = t.getId();
            if (seen.contains(key)) continue;
            seen.add(key);
            ToolEntity latest = tools.findByTenantIdAndIdOrderByVersionDesc(tenantId, t.getId())
                    .get(0);
            if (!"HTTP".equals(latest.getHandlerKind())) continue;
            Map<String, Object> spec = readSpec(latest.getHandlerHttpSpecJson());
            Object urlTemplateObj = spec.get("urlTemplate");
            if (!(urlTemplateObj instanceof String urlTemplate)) continue;
            if (!isAbsoluteOrProtocolRelative(urlTemplate)) continue;

            String origin = extractOrigin(urlTemplate);
            if (tenantOrigin == null) {
                unsafe.add(new Unsafe(latest.getId(), latest.getVersion(), urlTemplate,
                        "TENANT_HAS_NO_HOST_BASE_URL"));
                continue;
            }
            if (origin == null || !origin.equalsIgnoreCase(tenantOrigin)) {
                unsafe.add(new Unsafe(latest.getId(), latest.getVersion(), urlTemplate,
                        "ORIGIN_NOT_TENANT_HOST"));
                continue;
            }
            String path = urlTemplate.substring(urlTemplate.indexOf(origin) + origin.length());
            if (path.isEmpty() || !path.startsWith("/")) path = "/" + path;
            candidates.add(new Candidate(latest.getId(), latest.getVersion(), urlTemplate, path));
        }
        return new Plan(candidates, unsafe);
    }

    @Transactional
    public Plan apply(String tenantId) {
        Plan p = plan(tenantId);
        for (Candidate c : p.candidates()) {
            ToolEntity src = tools.findById(new ToolEntity.PK(tenantId, c.toolId(), c.version()))
                    .orElseThrow();
            Map<String, Object> spec = readSpec(src.getHandlerHttpSpecJson());
            spec.put("urlTemplate", c.rewritten());

            int nextVersion = tools.findByTenantIdAndIdOrderByVersionDesc(tenantId, c.toolId())
                    .stream().mapToInt(ToolEntity::getVersion).max().orElse(0) + 1;
            ToolEntity bumped = new ToolEntity(tenantId, c.toolId(), nextVersion,
                    src.getInputSchemaJson(), src.getOutputSchemaJson(), "HTTP");
            bumped.setRequiresJson(src.getRequiresJson());
            bumped.setPolicyJson(src.getPolicyJson());
            bumped.setHandlerHttpSpecJson(writeJson(spec));
            tools.save(bumped);
        }
        return p;
    }

    private boolean isAbsoluteOrProtocolRelative(String s) {
        return s.startsWith("//") || s.indexOf("://") > 0;
    }

    private String extractOrigin(String url) {
        if (url.startsWith("//")) return null;
        int schemeEnd = url.indexOf("://");
        if (schemeEnd <= 0) return null;
        int pathStart = url.indexOf('/', schemeEnd + 3);
        return pathStart < 0
                ? url.toLowerCase()
                : url.substring(0, pathStart).toLowerCase();
    }

    private Map<String, Object> readSpec(String raw) {
        if (raw == null || raw.isBlank()) return new HashMap<>();
        try {
            return new HashMap<>(json.readValue(raw, MAP_REF));
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String writeJson(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("migrator: failed to encode JSON", e);
        }
    }
}
