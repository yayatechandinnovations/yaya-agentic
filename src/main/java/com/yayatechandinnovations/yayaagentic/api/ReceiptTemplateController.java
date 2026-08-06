package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.channel.chat.receipts.ReceiptTemplateService;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.persistence.ReceiptTemplateEntity;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.Map;

/**
 * Admin CRUD for receipt templates ({@code chat-experience-design.md} §7.4).
 * The element vocabulary is validated server-side (closed, domain-free set) so
 * the chat renderer contract can't be broken from the console; everything a
 * template *means* stays in tenant data bound at runtime.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/receipt-templates")
public class ReceiptTemplateController {

    public record TemplateRequest(String name, String kindLabel, Integer paperCols,
                                  String paperProfile, List<Map<String, Object>> elements,
                                  Map<String, Object> brand) {}

    public record TemplateResponse(String id, String name, String kindLabel, int paperCols,
                                   String paperProfile, List<Map<String, Object>> elements,
                                   Map<String, Object> brand, String createdAt, String updatedAt) {
        static TemplateResponse of(ReceiptTemplateEntity e) {
            return new TemplateResponse(e.getId(), e.getName(), e.getKindLabel(),
                    e.getPaperCols(), e.getPaperProfile(), e.getElements(), e.getBrand(),
                    e.getCreatedAt() == null ? null : e.getCreatedAt().toString(),
                    e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
        }
    }

    private final ReceiptTemplateService templates;
    private final WorkspaceAccessHelper workspaceAccess;
    private final TenantGuard tenantGuard;

    public ReceiptTemplateController(ReceiptTemplateService templates,
                                     WorkspaceAccessHelper workspaceAccess,
                                     TenantGuard tenantGuard) {
        this.templates = templates;
        this.workspaceAccess = workspaceAccess;
        this.tenantGuard = tenantGuard;
    }

    @GetMapping
    public List<TemplateResponse> list(@PathVariable("tenantId") String tenantId,
                                       ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.MEMBER);
        tenantGuard.requireExists(tenantId);
        return templates.list(tenantId).stream().map(TemplateResponse::of).toList();
    }

    @GetMapping("/{id}")
    public TemplateResponse get(@PathVariable("tenantId") String tenantId,
                                @PathVariable("id") String id,
                                ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.MEMBER);
        tenantGuard.requireExists(tenantId);
        return templates.find(tenantId, id).map(TemplateResponse::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    public ResponseEntity<TemplateResponse> create(@PathVariable("tenantId") String tenantId,
                                                   @RequestBody TemplateRequest req,
                                                   ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.MEMBER);
        tenantGuard.requireWritable(tenantId);
        try {
            ReceiptTemplateEntity created = templates.create(tenantId,
                    req.name(), req.kindLabel(),
                    req.paperCols() == null ? 32 : req.paperCols(),
                    req.paperProfile() == null ? "58mm" : req.paperProfile(),
                    req.elements(), req.brand());
            return ResponseEntity.status(HttpStatus.CREATED).body(TemplateResponse.of(created));
        } catch (ReceiptTemplateService.InvalidTemplateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public TemplateResponse update(@PathVariable("tenantId") String tenantId,
                                   @PathVariable("id") String id,
                                   @RequestBody TemplateRequest req,
                                   ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.MEMBER);
        tenantGuard.requireWritable(tenantId);
        try {
            return templates.update(tenantId, id, req.name(), req.kindLabel(),
                            req.paperCols() == null ? 32 : req.paperCols(),
                            req.paperProfile() == null ? "58mm" : req.paperProfile(),
                            req.elements(), req.brand())
                    .map(TemplateResponse::of)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        } catch (ReceiptTemplateService.InvalidTemplateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("tenantId") String tenantId,
                                       @PathVariable("id") String id,
                                       ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.MEMBER);
        tenantGuard.requireWritable(tenantId);
        return templates.delete(tenantId, id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
