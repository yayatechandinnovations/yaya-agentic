package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppTemplateDtos;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.TemplateId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.WhatsAppTemplateService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/**
 * Platform-admin view of WhatsApp templates: each template once per WhatsApp
 * account, with the workspaces that hold it, and the calls that decide who
 * gets which (whatsapp-shared-templates-design.md §5). Not workspace-scoped —
 * every route requires a platform admin or a platform-scope API key.
 */
@RestController
@RequestMapping("/v1/admin/whatsapp-accounts")
public class WhatsAppAccountTemplateController {

    private final WorkspaceAccessHelper workspaceAccess;
    private final TenantGuard tenantGuard;
    private final WhatsAppTemplateService templates;

    public WhatsAppAccountTemplateController(WorkspaceAccessHelper workspaceAccess,
                                             TenantGuard tenantGuard,
                                             WhatsAppTemplateService templates) {
        this.workspaceAccess = workspaceAccess;
        this.tenantGuard = tenantGuard;
        this.templates = templates;
    }

    /** The platform account, then every account a workspace connected itself. */
    @GetMapping
    public List<WhatsAppTemplateDtos.AccountResponse> accounts(ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        return templates.listAccounts().stream().map(WhatsAppTemplateDtos.AccountResponse::of).toList();
    }

    @GetMapping("/{accountKey}/templates")
    public List<WhatsAppTemplateDtos.AccountTemplateResponse> list(
            @PathVariable("accountKey") String accountKey, ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        return respond(templates.listForAccount(accountKey));
    }

    /** Declare a template that belongs to the account; share it afterwards. */
    @PutMapping("/{accountKey}/templates/by-name/{name}")
    public WhatsAppTemplateDtos.AccountTemplateResponse declare(
            @PathVariable("accountKey") String accountKey,
            @PathVariable("name") String name,
            @RequestBody WhatsAppTemplateDtos.TemplateDeclaration req,
            ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        return WhatsAppTemplateDtos.AccountTemplateResponse.of(
                templates.declareForAccount(accountKey, WhatsAppTemplateController.declaration(name, req)));
    }

    @PostMapping("/{accountKey}/templates/sync")
    public List<WhatsAppTemplateDtos.AccountTemplateResponse> sync(
            @PathVariable("accountKey") String accountKey, ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        return respond(templates.syncAccount(accountKey));
    }

    @PostMapping("/{accountKey}/templates/{templateId}/submit")
    public WhatsAppTemplateDtos.AccountTemplateResponse submit(
            @PathVariable("accountKey") String accountKey,
            @PathVariable("templateId") long templateId,
            ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        return WhatsAppTemplateDtos.AccountTemplateResponse.of(
                templates.submitForAccount(accountKey, new TemplateId(templateId)));
    }

    /** Leave templates out of the console lists; who may send them does not change. */
    @PostMapping("/{accountKey}/templates/hide")
    public List<WhatsAppTemplateDtos.AccountTemplateResponse> hide(
            @PathVariable("accountKey") String accountKey,
            @RequestBody WhatsAppTemplateDtos.TemplateSelection req,
            ServerWebExchange exchange) {
        return setHidden(accountKey, req, true, exchange);
    }

    @PostMapping("/{accountKey}/templates/unhide")
    public List<WhatsAppTemplateDtos.AccountTemplateResponse> unhide(
            @PathVariable("accountKey") String accountKey,
            @RequestBody WhatsAppTemplateDtos.TemplateSelection req,
            ServerWebExchange exchange) {
        return setHidden(accountKey, req, false, exchange);
    }

    /** Remove a template nobody holds and WhatsApp never approved. */
    @DeleteMapping("/{accountKey}/templates/{templateId}")
    public ResponseEntity<Void> delete(
            @PathVariable("accountKey") String accountKey,
            @PathVariable("templateId") long templateId,
            ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        templates.deleteForAccount(accountKey, new TemplateId(templateId));
        return ResponseEntity.noContent().build();
    }

    /** Share the template with a workspace. */
    @PutMapping("/{accountKey}/templates/{templateId}/holders/{tenantId}")
    public WhatsAppTemplateDtos.AccountTemplateResponse share(
            @PathVariable("accountKey") String accountKey,
            @PathVariable("templateId") long templateId,
            @PathVariable("tenantId") String tenantId,
            ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        tenantGuard.requireExists(tenantId);
        return WhatsAppTemplateDtos.AccountTemplateResponse.of(templates.share(accountKey,
                new TemplateId(templateId), new Ids.TenantId(tenantId), caller(exchange)));
    }

    /** Stop sharing the template with a workspace. */
    @DeleteMapping("/{accountKey}/templates/{templateId}/holders/{tenantId}")
    public WhatsAppTemplateDtos.AccountTemplateResponse unshare(
            @PathVariable("accountKey") String accountKey,
            @PathVariable("templateId") long templateId,
            @PathVariable("tenantId") String tenantId,
            ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        return WhatsAppTemplateDtos.AccountTemplateResponse.of(templates.unshare(accountKey,
                new TemplateId(templateId), new Ids.TenantId(tenantId), caller(exchange)));
    }

    private List<WhatsAppTemplateDtos.AccountTemplateResponse> setHidden(
            String accountKey, WhatsAppTemplateDtos.TemplateSelection req, boolean hidden,
            ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        return respond(templates.setHiddenForAccount(accountKey,
                WhatsAppTemplateController.templateIds(req), hidden, caller(exchange)));
    }

    private WhatsAppTemplateService.Caller caller(ServerWebExchange exchange) {
        return new WhatsAppTemplateService.Caller(true, workspaceAccess.actorOf(exchange));
    }

    private static List<WhatsAppTemplateDtos.AccountTemplateResponse> respond(
            List<WhatsAppTemplateService.AccountTemplate> rows) {
        return rows.stream().map(WhatsAppTemplateDtos.AccountTemplateResponse::of).toList();
    }
}
