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
import org.springframework.dao.DataIntegrityViolationException;
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
        return respond(templates.listForSender(new Ids.TenantId(tenantId), new SenderId(senderId)),
                tenantId, senderId);
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
                req.name(), req.locale(), req.category(), req.body(), req.businessLabelVar(), caller(exchange));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(WhatsAppTemplateDtos.TemplateResponse.of(saved, tenantId, senderId));
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
                new Ids.TenantId(tenantId), new SenderId(senderId), new TemplateId(templateId),
                req.body(), cat, req.businessLabelVar(), caller(exchange));
        return WhatsAppTemplateDtos.TemplateResponse.of(updated, tenantId, senderId);
    }

    /**
     * Declarative upsert by name — how an integration's provisioning converges
     * the templates its catalog declares (whatsapp-ops-integration-design.md §14.6).
     * On an account several workspaces send from, a platform admin's (or a
     * platform-scope key's) declaration of a template that already exists
     * shares it with this workspace (whatsapp-shared-templates-design.md §4.2).
     */
    @PutMapping("/by-name/{name}")
    public WhatsAppTemplateDtos.TemplateResponse declare(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("senderId") String senderId,
            @PathVariable("name") String name,
            @RequestBody WhatsAppTemplateDtos.TemplateDeclaration req,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        WhatsAppTemplateService.Declaration declaration = declaration(name, req);
        WhatsAppTemplateService.Caller caller = caller(exchange);
        Ids.TenantId tenant = new Ids.TenantId(tenantId);
        SenderId sender = new SenderId(senderId);
        WhatsAppTemplate saved;
        try {
            saved = templates.declare(tenant, sender, declaration, caller);
        } catch (DataIntegrityViolationException raced) {
            // Another workspace of the same account declared the name at the
            // same moment; the second look finds its row.
            saved = templates.declare(tenant, sender, declaration, caller);
        }
        return WhatsAppTemplateDtos.TemplateResponse.of(saved, tenantId, senderId);
    }

    @PostMapping("/sync")
    public List<WhatsAppTemplateDtos.TemplateResponse> sync(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("senderId") String senderId,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        return respond(templates.syncSender(new Ids.TenantId(tenantId), new SenderId(senderId)),
                tenantId, senderId);
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
                new Ids.TenantId(tenantId), new SenderId(senderId), new TemplateId(templateId));
        return WhatsAppTemplateDtos.TemplateResponse.of(submitted, tenantId, senderId);
    }

    /** The workspace stops using the template; other holders keep it. */
    @DeleteMapping("/{templateId}")
    public ResponseEntity<Void> delete(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("senderId") String senderId,
            @PathVariable("templateId") long templateId,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        templates.delete(new Ids.TenantId(tenantId), new SenderId(senderId),
                new TemplateId(templateId), caller(exchange));
        return ResponseEntity.noContent().build();
    }

    /**
     * Leave templates out of the console lists, or show them again
     * (whatsapp-shared-templates-design.md §10). Sending is unaffected.
     */
    @PostMapping("/hide")
    public List<WhatsAppTemplateDtos.TemplateResponse> hide(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("senderId") String senderId,
            @RequestBody WhatsAppTemplateDtos.TemplateSelection req,
            ServerWebExchange exchange) {
        return setHidden(tenantId, senderId, req, true, exchange);
    }

    @PostMapping("/unhide")
    public List<WhatsAppTemplateDtos.TemplateResponse> unhide(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("senderId") String senderId,
            @RequestBody WhatsAppTemplateDtos.TemplateSelection req,
            ServerWebExchange exchange) {
        return setHidden(tenantId, senderId, req, false, exchange);
    }

    private List<WhatsAppTemplateDtos.TemplateResponse> setHidden(
            String tenantId, String senderId, WhatsAppTemplateDtos.TemplateSelection req, boolean hidden,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        return respond(templates.setHidden(new Ids.TenantId(tenantId), new SenderId(senderId),
                templateIds(req == null ? null : req.templateIds()), hidden, caller(exchange)), tenantId, senderId);
    }

    static List<TemplateId> templateIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw AdminApiException.badRequest("missing_template_ids", "templateIds required");
        }
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw AdminApiException.badRequest("bad_template_id", "templateIds must be positive");
        }
        return ids.stream().map(TemplateId::new).toList();
    }

    private WhatsAppTemplateService.Caller caller(ServerWebExchange exchange) {
        return new WhatsAppTemplateService.Caller(workspaceAccess.isPlatformAdmin(exchange),
                workspaceAccess.actorOf(exchange));
    }

    private static List<WhatsAppTemplateDtos.TemplateResponse> respond(List<WhatsAppTemplate> rows,
                                                                       String tenantId, String senderId) {
        return rows.stream()
                .map(t -> WhatsAppTemplateDtos.TemplateResponse.of(t, tenantId, senderId))
                .toList();
    }

    /** The service's declaration of a request body (§14.5); the path's name wins. */
    static WhatsAppTemplateService.Declaration declaration(String name,
                                                           WhatsAppTemplateDtos.TemplateDeclaration req) {
        if (req == null) throw AdminApiException.badRequest("missing_body", "body required");
        if (req.name() != null && !req.name().equals(name)) {
            throw AdminApiException.badRequest("name_mismatch", "body name differs from the path");
        }
        var samples = req.samples();
        return new WhatsAppTemplateService.Declaration(name, req.language(), req.category(), req.header(),
                req.body(), req.buttons(),
                samples == null ? null : samples.body(),
                samples == null ? null : samples.mediaFilename(),
                req.businessLabelVar());
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
