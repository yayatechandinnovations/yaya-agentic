package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.SmsSenderDtos;
import com.yayatechandinnovations.yayaagentic.channel.sms.Acquisition;
import com.yayatechandinnovations.yayaagentic.channel.sms.SenderKind;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsCampaignId;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsMarket;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsSender;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsSenderId;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.Optional;

/**
 * Tenant-facing SMS sender CRUD + state-machine endpoints
 * ({@code sms-channel-design.md} §9), mirroring {@code WhatsAppSenderController}.
 * Twilio is invisible: no response or error here names it. Sender creation lands
 * in {@code DRAFT}; submit + the poller drive it toward ACTIVE behind the
 * campaign-APPROVED gate.
 *
 * <p>VIEWER may read; ADMIN may create / submit / drive transitions.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/sms/senders")
public class SmsSenderController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final SmsSenderService senders;

    public SmsSenderController(TenantGuard tenantGuard,
                               WorkspaceAccessHelper workspaceAccess,
                               SmsSenderService senders) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.senders = senders;
    }

    @GetMapping
    public List<SmsSenderDtos.SenderResponse> list(@PathVariable("tenantId") String tenantId,
                                                   ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return senders.listForTenant(new Ids.TenantId(tenantId)).stream()
                .map(SmsSenderDtos.SenderResponse::of)
                .toList();
    }

    @GetMapping("/{senderId}")
    public SmsSenderDtos.SenderResponse get(@PathVariable("tenantId") String tenantId,
                                            @PathVariable("senderId") String senderId,
                                            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        SmsSender s = senders.requireForTenant(new Ids.TenantId(tenantId), new SmsSenderId(senderId));
        return SmsSenderDtos.SenderResponse.of(s);
    }

    @PostMapping
    public ResponseEntity<SmsSenderDtos.SenderResponse> create(
            @PathVariable("tenantId") String tenantId,
            @RequestBody SmsSenderDtos.SenderRequest req,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);

        SmsSender s = senders.createDraft(new Ids.TenantId(tenantId), toCommand(req));
        return ResponseEntity.status(HttpStatus.CREATED).body(SmsSenderDtos.SenderResponse.of(s));
    }

    @PostMapping("/{senderId}/submit")
    public SmsSenderDtos.SenderResponse submit(@PathVariable("tenantId") String tenantId,
                                               @PathVariable("senderId") String senderId,
                                               ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        return SmsSenderDtos.SenderResponse.of(
                senders.submit(new Ids.TenantId(tenantId), new SmsSenderId(senderId)));
    }

    @PostMapping("/{senderId}/sync")
    public SmsSenderDtos.SenderResponse sync(@PathVariable("tenantId") String tenantId,
                                             @PathVariable("senderId") String senderId,
                                             ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        return SmsSenderDtos.SenderResponse.of(
                senders.forceSync(new Ids.TenantId(tenantId), new SmsSenderId(senderId)));
    }

    @PostMapping("/{senderId}/regulatory-bundle")
    public SmsSenderDtos.SenderResponse attachBundle(@PathVariable("tenantId") String tenantId,
                                                     @PathVariable("senderId") String senderId,
                                                     @RequestBody SmsSenderDtos.RegulatoryBundleRequest req,
                                                     ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        if (req == null || req.regulatoryBundleSid() == null || req.regulatoryBundleSid().isBlank()) {
            throw AdminApiException.badRequest("missing_bundle", "regulatoryBundleSid required");
        }
        return SmsSenderDtos.SenderResponse.of(senders.attachRegulatoryBundle(
                new Ids.TenantId(tenantId), new SmsSenderId(senderId), req.regulatoryBundleSid()));
    }

    @PostMapping("/{senderId}/suspend")
    public SmsSenderDtos.SenderResponse suspend(@PathVariable("tenantId") String tenantId,
                                                @PathVariable("senderId") String senderId,
                                                ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        return SmsSenderDtos.SenderResponse.of(
                senders.suspend(new Ids.TenantId(tenantId), new SmsSenderId(senderId)));
    }

    @PostMapping("/{senderId}/resume")
    public SmsSenderDtos.SenderResponse resume(@PathVariable("tenantId") String tenantId,
                                               @PathVariable("senderId") String senderId,
                                               ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        return SmsSenderDtos.SenderResponse.of(
                senders.resume(new Ids.TenantId(tenantId), new SmsSenderId(senderId)));
    }

    @PostMapping("/{senderId}/revoke")
    public SmsSenderDtos.SenderResponse revoke(@PathVariable("tenantId") String tenantId,
                                               @PathVariable("senderId") String senderId,
                                               ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        return SmsSenderDtos.SenderResponse.of(
                senders.revoke(new Ids.TenantId(tenantId), new SmsSenderId(senderId)));
    }

    /* ---------------------------- helpers --------------------------------- */

    private static SmsSenderService.CreateSenderCommand toCommand(SmsSenderDtos.SenderRequest req) {
        if (req == null) throw AdminApiException.badRequest("missing_body", "request body required");
        return new SmsSenderService.CreateSenderCommand(
                req.phoneE164(),
                parseEnum(SmsMarket.class, req.market(), "market", true),
                parseEnum(SenderKind.class, req.senderKind(), "senderKind", false),
                parseEnum(Acquisition.class, req.acquisition(), "acquisition", false),
                req.campaignId() == null || req.campaignId().isBlank() ? null : new SmsCampaignId(req.campaignId()),
                Optional.ofNullable(req.defaultLocale()));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field, boolean required) {
        if (raw == null || raw.isBlank()) {
            if (required) throw AdminApiException.badRequest("missing_" + field, field + " required");
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw AdminApiException.badRequest("bad_" + field, "unknown " + field + " '" + raw + "'");
        }
    }
}
