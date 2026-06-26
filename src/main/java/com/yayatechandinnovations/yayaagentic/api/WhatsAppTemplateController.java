package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppTemplateDtos;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.TemplateCategory;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.TemplateId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.WhatsAppTemplate;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates.WhatsAppTemplateService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/** Admin CRUD for WhatsApp message templates per §14. */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/whatsapp-senders/{senderId}/templates")
public class WhatsAppTemplateController {

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final WhatsAppTemplateService templates;

    public WhatsAppTemplateController(TenantGuard tenantGuard,
                                      WorkspaceAccessHelper workspaceAccess,
                                      WhatsAppTemplateService templates) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.templates = templates;
    }

    @GetMapping
    public List<WhatsAppTemplateDtos.TemplateResponse> list(@PathVariable("tenantId") String tenantId,
                                                            @PathVariable("senderId") String senderId,
                                                            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        return templates.listForSender(new Ids.TenantId(tenantId), new SenderId(senderId))
                .stream().map(WhatsAppTemplateDtos.TemplateResponse::of).toList();
    }

    @PostMapping
    public ResponseEntity<WhatsAppTemplateDtos.TemplateResponse> create(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("senderId") String senderId,
            @RequestBody WhatsAppTemplateDtos.TemplateRequest req,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        validateCreate(req);
        WhatsAppTemplate saved = templates.create(
                new Ids.TenantId(tenantId), new SenderId(senderId),
                req.name(), req.locale(), req.category(), req.body());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(WhatsAppTemplateDtos.TemplateResponse.of(saved));
    }

    @PutMapping("/{templateId}")
    public WhatsAppTemplateDtos.TemplateResponse update(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("senderId") String senderId,
            @PathVariable("templateId") long templateId,
            @RequestBody WhatsAppTemplateDtos.TemplateUpdate req,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        if (req == null) {
            throw AdminApiException.badRequest("missing_body", "body required");
        }
        TemplateCategory cat = req.category() == null ? TemplateCategory.UTILITY : req.category();
        WhatsAppTemplate updated = templates.update(
                new Ids.TenantId(tenantId), new TemplateId(templateId), req.body(), cat);
        return WhatsAppTemplateDtos.TemplateResponse.of(updated);
    }

    @PostMapping("/{templateId}/submit")
    public WhatsAppTemplateDtos.TemplateResponse submit(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("senderId") String senderId,
            @PathVariable("templateId") long templateId,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        WhatsAppTemplate submitted = templates.submit(
                new Ids.TenantId(tenantId), new TemplateId(templateId));
        return WhatsAppTemplateDtos.TemplateResponse.of(submitted);
    }

    @DeleteMapping("/{templateId}")
    public ResponseEntity<Void> delete(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("senderId") String senderId,
            @PathVariable("templateId") long templateId,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        templates.delete(new Ids.TenantId(tenantId), new TemplateId(templateId));
        return ResponseEntity.noContent().build();
    }

    private static void validateCreate(WhatsAppTemplateDtos.TemplateRequest req) {
        if (req == null) throw AdminApiException.badRequest("missing_body", "body required");
        if (req.name() == null || req.name().isBlank())
            throw AdminApiException.badRequest("bad_name", "name required");
        if (req.locale() == null || req.locale().isBlank())
            throw AdminApiException.badRequest("bad_locale", "locale required");
        if (req.category() == null)
            throw AdminApiException.badRequest("bad_category", "category required");
        if (req.body() == null || req.body().isBlank())
            throw AdminApiException.badRequest("bad_body", "body required");
    }
}
