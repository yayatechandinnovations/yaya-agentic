package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppSenderDtos;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatLogoService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.BusinessProfile;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderTransferService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.shared.SenderAssignmentService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.shared.SenderMemberService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Admin CRUD + state-machine endpoints for WhatsApp senders, per
 * {@code whatsapp-channel-design.md} §14. Twilio is invisible to the tenant
 * operator — no endpoint here speaks the word "Twilio" in any error or
 * response field. Sender creation always lands in {@code DRAFT}; the actual
 * Twilio submit and the polling glue arrive in milestone W2.
 *
 * <p>VIEWER may list; ADMIN may create / edit / drive state transitions.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/whatsapp-senders")
public class WhatsAppSenderController {

    /** WhatsApp profile photos must be PNG or JPEG — Meta rejects the WebP
     *  and GIF that chat entry-point logos also accept. */
    private static final Map<String, String> LOGO_EXTENSIONS =
            Map.of("image/png", "png", "image/jpeg", "jpg");

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final WhatsAppSenderService senders;
    private final WhatsAppSenderTransferService transfers;
    private final ChatLogoService logos;
    private final SenderAssignmentService assignments;
    private final SenderMemberService members;

    public WhatsAppSenderController(TenantGuard tenantGuard,
                                    WorkspaceAccessHelper workspaceAccess,
                                    WhatsAppSenderService senders,
                                    WhatsAppSenderTransferService transfers,
                                    ChatLogoService logos,
                                    SenderAssignmentService assignments,
                                    SenderMemberService members) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.senders = senders;
        this.transfers = transfers;
        this.logos = logos;
        this.assignments = assignments;
        this.members = members;
    }

    @GetMapping
    public List<WhatsAppSenderDtos.SenderResponse> list(@PathVariable("tenantId") String tenantId,
                                                        ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        Ids.TenantId tenant = new Ids.TenantId(tenantId);
        List<WhatsAppSenderDtos.SenderResponse> out = new java.util.ArrayList<>(
                senders.listForTenant(tenant).stream().map(WhatsAppSenderDtos.SenderResponse::of).toList());
        // Then every platform sender this workspace is assigned to (shared-sender design §17.1).
        for (WhatsAppSender p : senders.listPlatform()) {
            assignments.forTenant(tenant, p.id()).ifPresent(v ->
                    out.add(WhatsAppSenderDtos.SenderResponse.of(p, WhatsAppSenderDtos.AssignmentSummary.of(v))));
        }
        return out;
    }

    @GetMapping("/{senderId}")
    public WhatsAppSenderDtos.SenderResponse get(@PathVariable("tenantId") String tenantId,
                                                 @PathVariable("senderId") String senderId,
                                                 ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        Ids.TenantId tenant = new Ids.TenantId(tenantId);
        WhatsAppSender s = senders.requireUsable(tenant, new SenderId(senderId));
        return WhatsAppSenderDtos.SenderResponse.of(s, assignments.forTenant(tenant, s.id())
                .map(WhatsAppSenderDtos.AssignmentSummary::of).orElse(null));
    }

    /* ------------------- assigned platform senders (§17.1) ------------------- */

    /** The workspace's own reply to people who aren't its staff on a shared number. */
    @PutMapping("/{senderId}/assignment")
    public WhatsAppSenderDtos.AssignmentSummary updateAssignment(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("senderId") String senderId,
            @RequestBody WhatsAppSenderDtos.AssignmentUpdate req,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        return WhatsAppSenderDtos.AssignmentSummary.of(assignments.setNonMemberReply(
                new Ids.TenantId(tenantId), new SenderId(senderId), req == null ? null : req.nonMemberReply()));
    }

    @GetMapping("/{senderId}/members")
    public WhatsAppSenderDtos.MemberSet listMembers(@PathVariable("tenantId") String tenantId,
                                                    @PathVariable("senderId") String senderId,
                                                    ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.VIEWER);
        tenantGuard.requireExists(tenantId);
        List<String> phones = members.list(new Ids.TenantId(tenantId), new SenderId(senderId));
        return new WhatsAppSenderDtos.MemberSet(phones, phones.size());
    }

    /** Full replace: the workspace's staff on the shared number are exactly these phones. */
    @PutMapping("/{senderId}/members")
    public WhatsAppSenderDtos.MemberSet replaceMembers(@PathVariable("tenantId") String tenantId,
                                                       @PathVariable("senderId") String senderId,
                                                       @RequestBody WhatsAppSenderDtos.MemberSet req,
                                                       ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        if (req == null || req.phones() == null) {
            throw AdminApiException.badRequest("missing_phones", "phones required (an empty list clears them)");
        }
        List<String> phones = members.replaceAll(new Ids.TenantId(tenantId), new SenderId(senderId), req.phones());
        return new WhatsAppSenderDtos.MemberSet(phones, phones.size());
    }

    @PutMapping("/{senderId}/members/{phone}")
    public ResponseEntity<Void> putMember(@PathVariable("tenantId") String tenantId,
                                          @PathVariable("senderId") String senderId,
                                          @PathVariable("phone") String phone,
                                          ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        members.put(new Ids.TenantId(tenantId), new SenderId(senderId), phone);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{senderId}/members/{phone}")
    public ResponseEntity<Void> deleteMember(@PathVariable("tenantId") String tenantId,
                                             @PathVariable("senderId") String senderId,
                                             @PathVariable("phone") String phone,
                                             ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        members.delete(new Ids.TenantId(tenantId), new SenderId(senderId), phone);
        return ResponseEntity.noContent().build();
    }

    @PostMapping
    public ResponseEntity<WhatsAppSenderDtos.SenderResponse> create(
            @PathVariable("tenantId") String tenantId,
            @RequestBody WhatsAppSenderDtos.SenderRequest req,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        validateBody(req);

        WhatsAppSender s = senders.createDraft(
                new Ids.TenantId(tenantId),
                req.phoneE164(),
                req.displayName(),
                req.businessProfile().toDomain(),
                Optional.ofNullable(req.defaultLocale()),
                Optional.ofNullable(req.startFlowId()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(WhatsAppSenderDtos.SenderResponse.of(s));
    }

    @PutMapping("/{senderId}")
    public WhatsAppSenderDtos.SenderResponse update(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("senderId") String senderId,
            @RequestBody WhatsAppSenderDtos.SenderRequest req,
            ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        validateBody(req);
        refusePlatform(tenantId, senderId);

        WhatsAppSender s = senders.editDraft(
                new Ids.TenantId(tenantId),
                new SenderId(senderId),
                req.phoneE164(),
                req.displayName(),
                req.businessProfile().toDomain(),
                Optional.ofNullable(req.defaultLocale()),
                Optional.ofNullable(req.startFlowId()));
        return WhatsAppSenderDtos.SenderResponse.of(s);
    }

    /** Hosts the sender's profile photo at a stable public URL for Twilio to
     *  fetch; the wizard stores the returned URL as {@code businessProfile.logoUrl}. */
    @PostMapping("/logos")
    public Mono<WhatsAppSenderDtos.LogoUploadResponse> uploadLogo(
            @PathVariable("tenantId") String tenantId,
            @RequestBody WhatsAppSenderDtos.LogoUploadRequest req,
            ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
                    workspaceAccess.require(tenantId, exchange, Role.ADMIN);
                    tenantGuard.requireWritable(tenantId);
                    if (req == null) throw AdminApiException.badRequest("missing_body", "request body required");
                    String contentType = req.contentType() == null
                            ? "" : req.contentType().trim().toLowerCase(Locale.ROOT);
                    String extension = LOGO_EXTENSIONS.get(contentType);
                    if (extension == null) {
                        throw AdminApiException.badRequest("bad_logo_type", "logo must be PNG or JPEG");
                    }
                    ChatLogoService.Stored stored = logos.store(
                            new Ids.TenantId(tenantId), contentType, req.dataBase64());
                    return new WhatsAppSenderDtos.LogoUploadResponse(
                            stored.url() + "/logo." + extension);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{senderId}/submit")
    public Mono<WhatsAppSenderDtos.SenderResponse> submit(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("senderId") String senderId,
            @RequestBody(required = false) WhatsAppSenderDtos.SubmitRequest req,
            ServerWebExchange exchange) {
        // A handler with a body resumes on the event loop — the blocking
        // Twilio call must hop to boundedElastic.
        return Mono.fromCallable(() -> {
                    workspaceAccess.require(tenantId, exchange, Role.ADMIN);
                    tenantGuard.requireWritable(tenantId);
                    refusePlatform(tenantId, senderId);
                    Optional<String> method = Optional.ofNullable(req)
                            .map(WhatsAppSenderDtos.SubmitRequest::verificationMethod)
                            .filter(m -> !m.isBlank());
                    WhatsAppSender s = senders.submit(
                            new Ids.TenantId(tenantId), new SenderId(senderId), method);
                    return WhatsAppSenderDtos.SenderResponse.of(s);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{senderId}/verify")
    public Mono<WhatsAppSenderDtos.SenderResponse> verify(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("senderId") String senderId,
            @RequestBody WhatsAppSenderDtos.VerifyRequest req,
            ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
                    workspaceAccess.require(tenantId, exchange, Role.ADMIN);
                    tenantGuard.requireWritable(tenantId);
                    refusePlatform(tenantId, senderId);
                    WhatsAppSender s = senders.verify(new Ids.TenantId(tenantId),
                            new SenderId(senderId), req == null ? null : req.code());
                    return WhatsAppSenderDtos.SenderResponse.of(s);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{senderId}/sync")
    public WhatsAppSenderDtos.SenderResponse sync(@PathVariable("tenantId") String tenantId,
                                                  @PathVariable("senderId") String senderId,
                                                  ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireExists(tenantId);
        refusePlatform(tenantId, senderId);
        WhatsAppSender s = senders.forceSync(new Ids.TenantId(tenantId), new SenderId(senderId));
        return WhatsAppSenderDtos.SenderResponse.of(s);
    }

    @PostMapping("/{senderId}/suspend")
    public WhatsAppSenderDtos.SenderResponse suspend(@PathVariable("tenantId") String tenantId,
                                                     @PathVariable("senderId") String senderId,
                                                     ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        refusePlatform(tenantId, senderId);
        WhatsAppSender s = senders.suspend(new Ids.TenantId(tenantId), new SenderId(senderId));
        return WhatsAppSenderDtos.SenderResponse.of(s);
    }

    @PostMapping("/{senderId}/resume")
    public WhatsAppSenderDtos.SenderResponse resume(@PathVariable("tenantId") String tenantId,
                                                    @PathVariable("senderId") String senderId,
                                                    ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        refusePlatform(tenantId, senderId);
        WhatsAppSender s = senders.resume(new Ids.TenantId(tenantId), new SenderId(senderId));
        return WhatsAppSenderDtos.SenderResponse.of(s);
    }

    @PostMapping("/{senderId}/revoke")
    public WhatsAppSenderDtos.SenderResponse revoke(@PathVariable("tenantId") String tenantId,
                                                    @PathVariable("senderId") String senderId,
                                                    ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        tenantGuard.requireWritable(tenantId);
        refusePlatform(tenantId, senderId);
        WhatsAppSender s = senders.revoke(new Ids.TenantId(tenantId), new SenderId(senderId));
        return WhatsAppSenderDtos.SenderResponse.of(s);
    }

    /**
     * Move the sender to another workspace, keeping its WhatsApp registration
     * (whatsapp-sender-transfer-design.md). Platform admins only (§5): moving
     * a number changes which business answers it.
     */
    @PostMapping("/{senderId}/transfer")
    public Mono<WhatsAppSenderDtos.TransferResponse> transfer(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("senderId") String senderId,
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun,
            @RequestBody WhatsAppSenderDtos.TransferRequest req,
            ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
                    workspaceAccess.requirePlatformAdmin(exchange);
                    tenantGuard.requireWritable(tenantId);
                    refusePlatform(tenantId, senderId);
                    var outcome = transfers.transfer(
                            new Ids.TenantId(tenantId), new SenderId(senderId),
                            req == null ? null : new WhatsAppSenderTransferService.TransferRequest(
                                    req.destinationTenant(), req.profileId(), req.profileVersion(),
                                    req.copyOptOuts(), req.destinationPlatform()),
                            workspaceAccess.actorOf(exchange), dryRun);
                    return WhatsAppSenderDtos.TransferResponse.of(outcome, dryRun);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Retry pointing WhatsApp at a transferred sender, after the automatic retries. */
    @PostMapping("/{senderId}/repoint-webhooks")
    public Mono<WhatsAppSenderDtos.SenderResponse> repointWebhooks(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("senderId") String senderId,
            ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
                    workspaceAccess.requirePlatformAdmin(exchange);
                    tenantGuard.requireWritable(tenantId);
                    refusePlatform(tenantId, senderId);
                    WhatsAppSender s = transfers.retryRepoint(new Ids.TenantId(tenantId), new SenderId(senderId));
                    return WhatsAppSenderDtos.SenderResponse.of(s);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /* ---------------------------- helpers --------------------------------- */

    /**
     * A platform sender's lifecycle belongs to platform admins
     * (/v1/admin/platform-senders); an assigned workspace sees it, never
     * changes it (shared-sender design §6, §17.1).
     */
    private void refusePlatform(String tenantId, String senderId) {
        boolean platform = senders.findById(new SenderId(senderId)).map(WhatsAppSender::isPlatform).orElse(false);
        if (platform && assignments.forTenant(new Ids.TenantId(tenantId), new SenderId(senderId)).isPresent()) {
            throw new AdminApiException(HttpStatus.FORBIDDEN, "platform_sender",
                    "this shared number is managed by the platform — ask a platform admin");
        }
    }

    private static void validateBody(WhatsAppSenderDtos.SenderRequest req) {
        if (req == null) {
            throw AdminApiException.badRequest("missing_body", "request body required");
        }
        if (req.phoneE164() == null || req.phoneE164().isBlank()) {
            throw AdminApiException.badRequest("bad_phone", "phoneE164 must not be blank");
        }
        if (req.displayName() == null || req.displayName().isBlank()) {
            throw AdminApiException.badRequest("bad_display_name", "displayName must not be blank");
        }
        if (req.businessProfile() == null) {
            throw AdminApiException.badRequest("missing_business_profile", "businessProfile required");
        }
        var bp = req.businessProfile();
        if (isBlank(bp.legalName()))    throw AdminApiException.badRequest("bad_business_profile", "legalName required");
        if (isBlank(bp.displayName()))  throw AdminApiException.badRequest("bad_business_profile", "businessProfile.displayName required");
        if (isBlank(bp.contactEmail())) throw AdminApiException.badRequest("bad_business_profile", "contactEmail required");
        if (isBlank(bp.vertical()))     throw AdminApiException.badRequest("bad_business_profile", "vertical required");
        if (!BusinessProfile.isValidWebsiteUrl(bp.websiteUrl())) {
            throw AdminApiException.badRequest("bad_business_profile",
                    "websiteUrl must be a web address such as https://example.com");
        }
        if (!BusinessProfile.isValidLogoUrl(bp.logoUrl())) {
            throw AdminApiException.badRequest("bad_business_profile",
                    "logoUrl must be an uploaded logo or a web address");
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
