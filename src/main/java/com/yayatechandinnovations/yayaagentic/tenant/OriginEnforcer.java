package com.yayatechandinnovations.yayaagentic.tenant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.persistence.AuditInboundOriginDeniedEntity;
import com.yayatechandinnovations.yayaagentic.persistence.AuditInboundOriginDeniedRepository;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Inbound origin filter for session endpoints per tenant-registry-design §5.
 *
 * <ul>
 *   <li>If no {@code Origin} header → skip (server-to-server / non-browser).</li>
 *   <li>If tenant unknown → skip; auth layer will reject downstream.</li>
 *   <li>Else: empty allowlist permits the host_base_url origin only; non-empty
 *       allowlist matches via {@link GlobMatcher}.</li>
 * </ul>
 *
 * This is a coarse first-line filter — not a substitute for the
 * {@code Authenticator}/{@code Authorizer} chain. It closes the
 * "third-party page tries to drive an authenticated session" surface.
 */
@Component
public class OriginEnforcer {

    private static final Logger LOG = LoggerFactory.getLogger(OriginEnforcer.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final TenantRepository tenants;
    private final AuditInboundOriginDeniedRepository audit;
    private final ObjectMapper json;

    public OriginEnforcer(TenantRepository tenants,
                          AuditInboundOriginDeniedRepository audit,
                          ObjectMapper json) {
        this.tenants = tenants;
        this.audit = audit;
        this.json = json;
    }

    public void requirePermitted(String tenantId, String origin, String path, String principal) {
        if (origin == null || origin.isBlank()) return;
        Optional<TenantEntity> maybe = (tenantId == null || tenantId.isBlank())
                ? Optional.empty()
                : tenants.findById(tenantId);
        if (maybe.isEmpty()) return;
        TenantEntity tenant = maybe.get();

        List<String> allowlist = parseAllowlist(tenant.getInboundOriginAllowlistJson());
        List<String> expected = new ArrayList<>(allowlist);

        if (allowlist.isEmpty()) {
            String defaultOrigin = BaseUrlValidator.originOf(tenant.getHostBaseUrl());
            if (defaultOrigin == null) {
                // No host base configured: nothing to compare against; let
                // auth reject (the tenant is misconfigured but that surfaces
                // via /v1/admin/tenants/{id}/health, not as a silent allow).
                return;
            }
            expected.add(defaultOrigin);
            if (defaultOrigin.equalsIgnoreCase(origin)) return;
        } else {
            for (String pattern : allowlist) {
                if (GlobMatcher.matches(pattern, origin)) return;
            }
        }

        recordDenial(tenantId, principal, origin, path, expected);
        throw AdminApiException.forbidden("origin_not_permitted",
                "origin '" + origin + "' is not permitted for tenant '" + tenantId + "'");
    }

    private List<String> parseAllowlist(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return json.readValue(raw, STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    private void recordDenial(String tenantId, String principal,
                              String origin, String path, List<String> expected) {
        try {
            audit.save(new AuditInboundOriginDeniedEntity(
                    tenantId, principal, origin, path, json.writeValueAsString(expected)));
        } catch (JsonProcessingException e) {
            // Audit failure must never block the denial. Log and move on.
            LOG.warn("origin_denial audit JSON encode failed: {}", e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("origin_denial audit write failed: {}", e.getMessage());
        }
    }
}
