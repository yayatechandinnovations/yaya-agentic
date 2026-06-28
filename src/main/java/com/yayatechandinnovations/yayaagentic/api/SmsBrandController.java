package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.SmsComplianceDtos;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsBrand;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsBrandId;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsComplianceService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

/**
 * Tenant-facing A2P 10DLC Brand endpoints, per {@code sms-channel-design.md}
 * §9. One Brand per tenant in v1, so the routes carry no {@code {brandId}} —
 * the tenant's single brand is resolved internally. Twilio / TCR are invisible:
 * no response or error here names them.
 *
 * <p>VIEWER may read; ADMIN may create / edit / submit.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/sms/brand")
public class SmsBrandController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final SmsComplianceService compliance;

    public SmsBrandController(TenantGuard tenantGuard,
                             WorkspaceAccessHelper workspaceAccess,
                             SmsComplianceService compliance) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.compliance = compliance;
    }

    @GetMapping
    public SmsComplianceDtos.BrandResponse get(@PathVariable("tenantId") String tenantId,
                                               ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        SmsBrand brand = compliance.findBrand(new Ids.TenantId(tenantId))
                .orElseThrow(() -> AdminApiException.notFound("no_brand",
                        "this tenant has no SMS brand yet — create one first"));
        return SmsComplianceDtos.BrandResponse.of(brand);
    }

    @PostMapping
    public ResponseEntity<SmsComplianceDtos.BrandResponse> create(
            @PathVariable("tenantId") String tenantId,
            @RequestBody SmsComplianceDtos.BrandRequest req,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        validate(req);
        SmsBrand brand = compliance.createBrand(new Ids.TenantId(tenantId), req.business().toDomain());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SmsComplianceDtos.BrandResponse.of(brand));
    }

    @PutMapping
    public SmsComplianceDtos.BrandResponse update(@PathVariable("tenantId") String tenantId,
                                                  @RequestBody SmsComplianceDtos.BrandRequest req,
                                                  ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        validate(req);
        SmsBrandId id = resolveBrandId(tenantId);
        SmsBrand brand = compliance.editBrand(new Ids.TenantId(tenantId), id, req.business().toDomain());
        return SmsComplianceDtos.BrandResponse.of(brand);
    }

    @PostMapping("/submit")
    public SmsComplianceDtos.BrandResponse submit(@PathVariable("tenantId") String tenantId,
                                                  ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        SmsBrandId id = resolveBrandId(tenantId);
        SmsBrand brand = compliance.submitBrand(new Ids.TenantId(tenantId), id);
        return SmsComplianceDtos.BrandResponse.of(brand);
    }

    private SmsBrandId resolveBrandId(String tenantId) {
        return compliance.findBrand(new Ids.TenantId(tenantId))
                .map(SmsBrand::id)
                .orElseThrow(() -> AdminApiException.notFound("no_brand",
                        "this tenant has no SMS brand yet — create one first"));
    }

    private static void validate(SmsComplianceDtos.BrandRequest req) {
        if (req == null || req.business() == null) {
            throw AdminApiException.badRequest("missing_body", "business identity required");
        }
        var b = req.business();
        if (isBlank(b.legalName())) throw AdminApiException.badRequest("bad_business", "legalName required");
        if (isBlank(b.taxId()))     throw AdminApiException.badRequest("bad_business", "taxId (EIN) required");
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
