package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppKeywordActionDtos;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords.KeywordAction;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords.KeywordActionService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/**
 * Admin read/replace of a sender's keyword actions — the words a business
 * number answers deterministically by forwarding to the tenant (ticket-share
 * design §8 P5). Same access rules as the sibling template routes: VIEWER
 * reads, ADMIN writes, platform admins and the workspace's own both qualify.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/whatsapp-senders/{senderId}/keyword-actions")
public class WhatsAppKeywordActionController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final KeywordActionService actions;

    public WhatsAppKeywordActionController(TenantGuard tenantGuard,
                                           WorkspaceAccessHelper workspaceAccess,
                                           KeywordActionService actions) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.actions = actions;
    }

    @GetMapping
    public WhatsAppKeywordActionDtos.ActionSet list(@PathVariable("tenantId") String tenantId,
                                                    @PathVariable("senderId") String senderId,
                                                    ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return WhatsAppKeywordActionDtos.ActionSet.of(
                actions.list(new Ids.TenantId(tenantId), new SenderId(senderId)));
    }

    /** Replaces the set: what is not in the body is gone. */
    @PutMapping
    public WhatsAppKeywordActionDtos.ActionSet replace(@PathVariable("tenantId") String tenantId,
                                                       @PathVariable("senderId") String senderId,
                                                       @RequestBody WhatsAppKeywordActionDtos.ActionSet body,
                                                       ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        if (body == null || body.actions() == null) {
            throw AdminApiException.badRequest("missing_actions", "actions required (an empty list clears the set)");
        }
        List<KeywordAction> wanted;
        try {
            wanted = body.actions().stream()
                    .map(a -> new KeywordAction(a == null ? null : a.prefix(), a == null ? null : a.forwardUrl(),
                            a == null ? null : scope(a.scope())))
                    .toList();
        } catch (IllegalArgumentException e) {
            throw AdminApiException.badRequest("bad_action", e.getMessage());
        }
        return WhatsAppKeywordActionDtos.ActionSet.of(
                actions.replaceAll(new Ids.TenantId(tenantId), new SenderId(senderId), wanted));
    }

    private static KeywordAction.Scope scope(String raw) {
        if (raw == null || raw.isBlank()) return KeywordAction.Scope.ROUTED;
        try {
            return KeywordAction.Scope.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("scope must be ROUTED or REPLY");
        }
    }
}
