package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.OptOutService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelOptOutEntity;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.time.Instant;
import java.util.List;

/**
 * Operator-tier opt-out management. Read + audited removal — Meta policy
 * forbids re-subscribing a user without their consent, so DELETE is meant
 * for incident response (mistaken opt-outs), not bulk re-engagement.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/channel-opt-outs")
public class ChannelOptOutController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final OptOutService optOuts;
    private final WhatsAppSenderService senders;

    public ChannelOptOutController(TenantGuard tenantGuard,
                                   WorkspaceAccessHelper workspaceAccess,
                                   OptOutService optOuts,
                                   WhatsAppSenderService senders) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.optOuts = optOuts;
        this.senders = senders;
    }

    @GetMapping
    public List<OptOutResponse> list(@PathVariable("tenantId") String tenantId,
                                     @RequestParam("senderId") String senderIdParam,
                                     ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        // Cross-tenant gate: sender must belong to tenant.
        senders.requireForTenant(new Ids.TenantId(tenantId), new SenderId(senderIdParam));
        return optOuts.listForSender(new SenderId(senderIdParam)).stream()
                .map(OptOutResponse::of).toList();
    }

    @DeleteMapping("/{senderId}/{phone}")
    public ResponseEntity<Void> remove(@PathVariable("tenantId") String tenantId,
                                       @PathVariable("senderId") String senderId,
                                       @PathVariable("phone") String phone,
                                       ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        senders.requireForTenant(new Ids.TenantId(tenantId), new SenderId(senderId));
        optOuts.resubscribe(new SenderId(senderId), phone);
        return ResponseEntity.noContent().build();
    }

    public record OptOutResponse(String senderId, String endUserPhone,
                                 Instant optedOutAt, String reason) {
        public static OptOutResponse of(ChannelOptOutEntity e) {
            return new OptOutResponse(
                    e.getSenderId(),
                    e.getEndUserPhone(),
                    e.getOptedOutAt() == null ? null : e.getOptedOutAt().toInstant(),
                    e.getReason());
        }
    }
}
