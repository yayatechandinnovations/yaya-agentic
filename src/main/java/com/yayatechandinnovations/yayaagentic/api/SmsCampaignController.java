package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.SmsComplianceDtos;
import com.yayatechandinnovations.yayaagentic.channel.sms.A2pUseCase;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsBrandId;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsCampaign;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsCampaignId;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsComplianceService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/**
 * Tenant-facing A2P 10DLC Campaign endpoints, per {@code sms-channel-design.md}
 * §9. A campaign cannot be submitted until its Brand is VERIFIED and (for
 * restricted use cases) a platform operator has released it — both enforced in
 * {@link SmsComplianceService}, surfaced here as 409s.
 *
 * <p>The operator-review <em>release</em> action is deliberately NOT here: it
 * lives on the operator plane (§9), so a tenant cannot self-approve a
 * restricted campaign.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/sms/campaigns")
public class SmsCampaignController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final SmsComplianceService compliance;

    public SmsCampaignController(TenantGuard tenantGuard,
                                 WorkspaceAccessHelper workspaceAccess,
                                 SmsComplianceService compliance) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.compliance = compliance;
    }

    @GetMapping
    public List<SmsComplianceDtos.CampaignResponse> list(@PathVariable("tenantId") String tenantId,
                                                         ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return compliance.listCampaigns(new Ids.TenantId(tenantId)).stream()
                .map(SmsComplianceDtos.CampaignResponse::of)
                .toList();
    }

    @GetMapping("/{campaignId}")
    public SmsComplianceDtos.CampaignResponse get(@PathVariable("tenantId") String tenantId,
                                                  @PathVariable("campaignId") String campaignId,
                                                  ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        SmsCampaign c = compliance.requireCampaign(new Ids.TenantId(tenantId), new SmsCampaignId(campaignId));
        return SmsComplianceDtos.CampaignResponse.of(c);
    }

    @PostMapping
    public ResponseEntity<SmsComplianceDtos.CampaignResponse> create(
            @PathVariable("tenantId") String tenantId,
            @RequestBody SmsComplianceDtos.CampaignRequest req,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        A2pUseCase useCase = parseUseCase(req);

        SmsCampaign c = compliance.createCampaign(new Ids.TenantId(tenantId),
                new SmsComplianceService.CreateCampaignCommand(
                        new SmsBrandId(req.brandId()),
                        useCase,
                        req.description(),
                        req.sampleMessages(),
                        req.optIn() == null ? null : req.optIn().toDomain(),
                        req.evidence()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SmsComplianceDtos.CampaignResponse.of(c));
    }

    @PostMapping("/{campaignId}/submit")
    public SmsComplianceDtos.CampaignResponse submit(@PathVariable("tenantId") String tenantId,
                                                     @PathVariable("campaignId") String campaignId,
                                                     ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        SmsCampaign c = compliance.submitCampaign(new Ids.TenantId(tenantId), new SmsCampaignId(campaignId));
        return SmsComplianceDtos.CampaignResponse.of(c);
    }

    private static A2pUseCase parseUseCase(SmsComplianceDtos.CampaignRequest req) {
        if (req == null || req.brandId() == null || req.brandId().isBlank()) {
            throw AdminApiException.badRequest("missing_brand", "brandId required");
        }
        if (req.useCase() == null || req.useCase().isBlank()) {
            throw AdminApiException.badRequest("missing_use_case", "useCase required");
        }
        try {
            return A2pUseCase.valueOf(req.useCase().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw AdminApiException.badRequest("bad_use_case", "unknown useCase '" + req.useCase() + "'");
        }
    }
}
