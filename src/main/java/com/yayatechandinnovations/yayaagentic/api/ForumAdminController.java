package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.ForumDtos;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.forum.ForumService;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/**
 * Forum administration ({@code chat-experience-design.md} §8.1–§8.2): the
 * per-entry-point switch, the moderation queue (hide/unhide/pin/delete, open
 * reports), and posting as the brand.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/forum")
public class ForumAdminController {

    private final ForumService forum;
    private final ChatEntryPointService entryPoints;
    private final WorkspaceAccessHelper workspaceAccess;
    private final TenantGuard tenantGuard;

    public ForumAdminController(ForumService forum,
                                ChatEntryPointService entryPoints,
                                WorkspaceAccessHelper workspaceAccess,
                                TenantGuard tenantGuard) {
        this.forum = forum;
        this.entryPoints = entryPoints;
        this.workspaceAccess = workspaceAccess;
        this.tenantGuard = tenantGuard;
    }

    /* ------------------------------ settings -------------------------------- */

    @GetMapping("/entry-points/{entryPointId}/settings")
    public ForumDtos.SettingsResponse settings(@PathVariable("tenantId") String tenantId,
                                               @PathVariable("entryPointId") String entryPointId,
                                               ServerWebExchange exchange) {
        requireMember(tenantId, exchange);
        requireEntryPoint(tenantId, entryPointId);
        return new ForumDtos.SettingsResponse(forum.isEnabled(entryPointId));
    }

    @PutMapping("/entry-points/{entryPointId}/settings")
    public ForumDtos.SettingsResponse update(@PathVariable("tenantId") String tenantId,
                                             @PathVariable("entryPointId") String entryPointId,
                                             @RequestBody ForumDtos.SettingsRequest body,
                                             ServerWebExchange exchange) {
        requireMember(tenantId, exchange);
        tenantGuard.requireWritable(tenantId);
        requireEntryPoint(tenantId, entryPointId);
        if (body == null || body.enabled() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled is required");
        }
        forum.setEnabled(tenantId, entryPointId, body.enabled());
        return new ForumDtos.SettingsResponse(body.enabled());
    }

    /* ----------------------------- moderation ------------------------------- */

    @GetMapping("/entry-points/{entryPointId}/posts")
    public List<ForumDtos.AdminPostView> posts(@PathVariable("tenantId") String tenantId,
                                               @PathVariable("entryPointId") String entryPointId,
                                               ServerWebExchange exchange) {
        requireMember(tenantId, exchange);
        requireEntryPoint(tenantId, entryPointId);
        return forum.adminPosts(entryPointId).stream().map(ForumDtos.AdminPostView::of).toList();
    }

    @PostMapping("/entry-points/{entryPointId}/posts")
    public ForumDtos.AdminPostView postAsBrand(@PathVariable("tenantId") String tenantId,
                                               @PathVariable("entryPointId") String entryPointId,
                                               @RequestBody ForumDtos.OperatorPostRequest body,
                                               ServerWebExchange exchange) {
        requireMember(tenantId, exchange);
        tenantGuard.requireWritable(tenantId);
        ChatEntryPoint ep = requireEntryPoint(tenantId, entryPointId);
        try {
            String display = body != null && body.displayName() != null && !body.displayName().isBlank()
                    ? body.displayName().trim() : ep.branding().displayName();
            return ForumDtos.AdminPostView.of(forum.postAsOperator(tenantId, entryPointId,
                    display, body == null ? null : body.body(),
                    body != null && Boolean.TRUE.equals(body.pinned())));
        } catch (ForumService.ForumException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PatchMapping("/posts/{postId}")
    public ForumDtos.AdminPostView moderate(@PathVariable("tenantId") String tenantId,
                                            @PathVariable("postId") String postId,
                                            @RequestBody ForumDtos.ModerateRequest body,
                                            ServerWebExchange exchange) {
        requireMember(tenantId, exchange);
        tenantGuard.requireWritable(tenantId);
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing body");
        return forum.moderate(tenantId, postId, body.hidden(), body.pinned(),
                        Boolean.TRUE.equals(body.delete()))
                .map(ForumDtos.AdminPostView::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping("/reports")
    public ForumDtos.ReportsResponse reports(@PathVariable("tenantId") String tenantId,
                                             ServerWebExchange exchange) {
        requireMember(tenantId, exchange);
        return new ForumDtos.ReportsResponse(
                forum.openReports(tenantId).stream().map(ForumDtos.ReportView::of).toList());
    }

    /* ------------------------------ helpers --------------------------------- */

    private void requireMember(String tenantId, ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.MEMBER);
        tenantGuard.requireExists(tenantId);
    }

    private ChatEntryPoint requireEntryPoint(String tenantId, String entryPointId) {
        try {
            return entryPoints.requireForTenant(new Ids.TenantId(tenantId),
                    new com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointId(entryPointId));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }
}
