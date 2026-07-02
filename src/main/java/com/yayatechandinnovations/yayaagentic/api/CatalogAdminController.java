package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.dto.CatalogAdminDtos;
import com.yayatechandinnovations.yayaagentic.commerce.CatalogLocation;
import com.yayatechandinnovations.yayaagentic.commerce.CatalogSyncPolicy;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalog;
import com.yayatechandinnovations.yayaagentic.commerce.ProductCatalogRegistry;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalPolicy;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin REST for product catalogs — the store analog of
 * {@link KnowledgeAdminController}. A {@code POST} creates the next version (the
 * same convention profiles/tools use), mapping the operator's
 * {@code locationKind} + {@code location} JSON onto the sealed
 * {@link CatalogLocation} family. See {@code ecommerce-chat-design.md} §15.
 */
@RestController
@RequestMapping("/v1/admin/product-catalogs")
public class CatalogAdminController {

    private static final TypeReference<CatalogSyncPolicy> SYNC = new TypeReference<>() {};
    private static final TypeReference<RetrievalPolicy> SEARCH = new TypeReference<>() {};
    private static final TypeReference<PermissionRequirement> ACCESS = new TypeReference<>() {};

    private final ProductCatalogRegistry catalogs;
    private final ObjectMapper json;
    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;

    public CatalogAdminController(ProductCatalogRegistry catalogs, ObjectMapper json,
                                  TenantGuard tenantGuard, WorkspaceAccessHelper workspaceAccess) {
        this.catalogs = catalogs;
        this.json = json;
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
    }

    @GetMapping
    public CatalogAdminDtos.ListResponse list(
            @RequestParam(value = "tenant", required = false) String tenant,
            ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.VIEWER);
        return new CatalogAdminDtos.ListResponse(
                catalogs.listFor(new Ids.TenantId(resolved)).stream().map(this::toView).toList());
    }

    @GetMapping("/{id}")
    public CatalogAdminDtos.ProductCatalogDetail get(
            @RequestParam(value = "tenant", required = false) String tenant,
            @PathVariable("id") String id,
            ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.VIEWER);
        return toDetail(findLatestOrThrow(resolved, id));
    }

    @PostMapping
    @Transactional
    public CatalogAdminDtos.ProductCatalogView create(
            @RequestBody CatalogAdminDtos.CreateProductCatalogRequest req,
            ServerWebExchange exchange) {
        String tenant = workspaceAccess.require(req.tenant(), exchange, Role.MEMBER);
        tenantGuard.requireWritable(tenant);
        require(req.id(), "id");
        require(req.displayName(), "displayName");
        require(req.currency(), "currency");

        int version = nextVersion(tenant, req.id());
        ProductCatalog catalog = new ProductCatalog(
                new Ids.ProductCatalogId(req.id(), version),
                new Ids.TenantId(tenant),
                req.displayName(),
                location(req.locationKind(), req.location()),
                sync(req.sync()), search(req.search()), access(req.access()),
                req.currency(), blankToNull(req.imageUrl()));
        catalogs.register(catalog);
        return toView(catalog);
    }

    @PutMapping("/{id}")
    @Transactional
    public CatalogAdminDtos.ProductCatalogView update(
            @RequestParam(value = "tenant", required = false) String tenant,
            @PathVariable("id") String id,
            @RequestBody CatalogAdminDtos.UpdateProductCatalogRequest req,
            ServerWebExchange exchange) {
        String resolved = workspaceAccess.require(tenant, exchange, Role.MEMBER);
        tenantGuard.requireWritable(resolved);
        findLatestOrThrow(resolved, id);   // 404 if it doesn't exist yet
        int version = nextVersion(resolved, id);
        ProductCatalog catalog = new ProductCatalog(
                new Ids.ProductCatalogId(id, version),
                new Ids.TenantId(resolved),
                req.displayName(),
                location(req.locationKind(), req.location()),
                sync(req.sync()), search(req.search()), access(req.access()),
                req.currency(), blankToNull(req.imageUrl()));
        catalogs.register(catalog);
        return toView(catalog);
    }

    // ---- mapping ----------------------------------------------------------

    private CatalogLocation location(String kind, Map<String, Object> location) {
        if (kind == null || kind.isBlank()) {
            throw badRequest("locationKind is required (INLINE | HTTP_API)");
        }
        if (!kind.equals("INLINE") && !kind.equals("HTTP_API")) {
            throw badRequest("unsupported locationKind: " + kind + " (INLINE | HTTP_API)");
        }
        Map<String, Object> withKind = new LinkedHashMap<>(location == null ? Map.of() : location);
        withKind.put("kind", kind);   // the @JsonTypeInfo discriminator
        try {
            return json.convertValue(withKind, CatalogLocation.class);
        } catch (IllegalArgumentException e) {
            throw badRequest("invalid location for kind " + kind + ": " + e.getMessage());
        }
    }

    private CatalogSyncPolicy sync(Map<String, Object> m) {
        return m == null || m.isEmpty() ? CatalogSyncPolicy.live() : json.convertValue(m, SYNC);
    }

    private RetrievalPolicy search(Map<String, Object> m) {
        return m == null || m.isEmpty() ? RetrievalPolicy.defaults() : json.convertValue(m, SEARCH);
    }

    private PermissionRequirement access(Map<String, Object> m) {
        return m == null || m.isEmpty() ? PermissionRequirement.none() : json.convertValue(m, ACCESS);
    }

    private int nextVersion(String tenant, String id) {
        return catalogs.listFor(new Ids.TenantId(tenant)).stream()
                .filter(c -> c.id().value().equals(id))
                .mapToInt(c -> c.id().version())
                .max().orElse(0) + 1;
    }

    private ProductCatalog findLatestOrThrow(String tenant, String id) {
        return catalogs.listFor(new Ids.TenantId(tenant)).stream()
                .filter(c -> c.id().value().equals(id))
                .max((a, b) -> Integer.compare(a.id().version(), b.id().version()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such catalog: " + id));
    }

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private String kindOf(ProductCatalog c) {
        return switch (c.location()) {
            case CatalogLocation.Inline i -> "INLINE";
            case CatalogLocation.HttpApi h -> "HTTP_API";
            case CatalogLocation.Database d -> "DATABASE";
        };
    }

    private CatalogAdminDtos.ProductCatalogView toView(ProductCatalog c) {
        int count = c.location() instanceof CatalogLocation.Inline inline ? inline.products().size() : -1;
        return new CatalogAdminDtos.ProductCatalogView(
                c.id().value(), c.id().version(), c.tenant().value(),
                c.displayName(), c.currency(), c.imageUrl(), kindOf(c), count);
    }

    private CatalogAdminDtos.ProductCatalogDetail toDetail(ProductCatalog c) {
        return new CatalogAdminDtos.ProductCatalogDetail(
                c.id().value(), c.id().version(), c.tenant().value(),
                c.displayName(), c.currency(), c.imageUrl(), kindOf(c),
                json.convertValue(c.location(), MAP));
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw badRequest(field + " is required");
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
