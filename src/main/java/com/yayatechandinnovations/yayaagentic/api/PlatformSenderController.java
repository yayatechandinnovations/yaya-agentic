package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.PlatformSenderDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.WhatsAppSenderDtos;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.BusinessProfile;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderTransferService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.shared.SenderAssignmentService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;

/**
 * Platform senders — WhatsApp numbers the platform owns and assigns to
 * workspaces (whatsapp-shared-sender-design.md §5, §17.3). Every route
 * requires a platform admin or a platform-scope API key; the lifecycle is the
 * one workspace senders have, with no workspace.
 */
@RestController
@RequestMapping("/v1/admin/platform-senders")
public class PlatformSenderController {

    private final WorkspaceAccessHelper workspaceAccess;
    private final WhatsAppSenderService senders;
    private final SenderAssignmentService assignments;
    private final WhatsAppSenderTransferService transfers;
    private final InboundMessageRepository inbound;

    public PlatformSenderController(WorkspaceAccessHelper workspaceAccess,
                                    WhatsAppSenderService senders,
                                    SenderAssignmentService assignments,
                                    WhatsAppSenderTransferService transfers,
                                    InboundMessageRepository inbound) {
        this.workspaceAccess = workspaceAccess;
        this.senders = senders;
        this.assignments = assignments;
        this.transfers = transfers;
        this.inbound = inbound;
    }

    @GetMapping
    public List<PlatformSenderDtos.PlatformSenderResponse> list(ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        return senders.listPlatform().stream().map(this::respond).toList();
    }

    @PostMapping
    public ResponseEntity<PlatformSenderDtos.PlatformSenderResponse> create(
            @RequestBody PlatformSenderDtos.PlatformSenderRequest req, ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        validate(req, true);
        WhatsAppSender s = senders.createDraft(null, req.phoneE164().trim(), req.displayName(),
                req.businessProfile().toDomain(), Optional.ofNullable(req.defaultLocale()), Optional.empty());
        if (req.unroutedReply() != null) s = senders.setUnroutedReply(s.id(), req.unroutedReply());
        return ResponseEntity.status(HttpStatus.CREATED).body(respond(s));
    }

    @GetMapping("/{senderId}")
    public PlatformSenderDtos.PlatformSenderResponse get(@PathVariable("senderId") String senderId,
                                                         ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        return respond(senders.requireForTenant(null, new SenderId(senderId)));
    }

    /**
     * The business profile and display name are editable while the number is
     * a DRAFT or FAILED registration, as for a workspace sender; the unrouted
     * reply always is.
     */
    @PutMapping("/{senderId}")
    public PlatformSenderDtos.PlatformSenderResponse update(@PathVariable("senderId") String senderId,
                                                            @RequestBody PlatformSenderDtos.PlatformSenderRequest req,
                                                            ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        if (req == null) throw AdminApiException.badRequest("missing_body", "request body required");
        SenderId id = new SenderId(senderId);
        WhatsAppSender s = senders.requireForTenant(null, id);
        if (s.isEditable() && req.businessProfile() != null) {
            validate(req, false);
            s = senders.editDraft(null, id, s.phoneE164(), req.displayName(), req.businessProfile().toDomain(),
                    Optional.ofNullable(req.defaultLocale()), Optional.empty());
        }
        if (req.unroutedReply() != null) s = senders.setUnroutedReply(id, req.unroutedReply());
        return respond(s);
    }

    @PostMapping("/{senderId}/submit")
    public Mono<PlatformSenderDtos.PlatformSenderResponse> submit(
            @PathVariable("senderId") String senderId,
            @RequestBody(required = false) WhatsAppSenderDtos.SubmitRequest req,
            ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
                    workspaceAccess.requirePlatformAdmin(exchange);
                    Optional<String> method = Optional.ofNullable(req)
                            .map(WhatsAppSenderDtos.SubmitRequest::verificationMethod)
                            .filter(m -> !m.isBlank());
                    return respond(senders.submit(null, new SenderId(senderId), method));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{senderId}/verify")
    public Mono<PlatformSenderDtos.PlatformSenderResponse> verify(
            @PathVariable("senderId") String senderId,
            @RequestBody WhatsAppSenderDtos.VerifyRequest req,
            ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
                    workspaceAccess.requirePlatformAdmin(exchange);
                    return respond(senders.verify(null, new SenderId(senderId), req == null ? null : req.code()));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{senderId}/sync")
    public Mono<PlatformSenderDtos.PlatformSenderResponse> sync(@PathVariable("senderId") String senderId,
                                                                ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
                    workspaceAccess.requirePlatformAdmin(exchange);
                    return respond(senders.forceSync(null, new SenderId(senderId)));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{senderId}/suspend")
    public PlatformSenderDtos.PlatformSenderResponse suspend(@PathVariable("senderId") String senderId,
                                                             ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        return respond(senders.suspend(null, new SenderId(senderId)));
    }

    @PostMapping("/{senderId}/resume")
    public PlatformSenderDtos.PlatformSenderResponse resume(@PathVariable("senderId") String senderId,
                                                            ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        return respond(senders.resume(null, new SenderId(senderId)));
    }

    /** Remove the number; refused while workspaces are still assigned to it. */
    @DeleteMapping("/{senderId}")
    public Mono<PlatformSenderDtos.PlatformSenderResponse> revoke(@PathVariable("senderId") String senderId,
                                                                  ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
                    workspaceAccess.requirePlatformAdmin(exchange);
                    return respond(senders.revoke(null, new SenderId(senderId)));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Move the number into one workspace (§5.2); refused while workspaces are assigned. */
    @PostMapping("/{senderId}/transfer")
    public Mono<WhatsAppSenderDtos.TransferResponse> transfer(
            @PathVariable("senderId") String senderId,
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun,
            @RequestBody PlatformSenderDtos.TransferToWorkspaceRequest req,
            ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
                    workspaceAccess.requirePlatformAdmin(exchange);
                    var outcome = transfers.transfer(null, new SenderId(senderId),
                            req == null ? null : new WhatsAppSenderTransferService.TransferRequest(
                                    req.destinationTenant(), req.profileId(), req.profileVersion(),
                                    req.copyOptOuts()),
                            workspaceAccess.actorOf(exchange), dryRun);
                    return WhatsAppSenderDtos.TransferResponse.of(outcome, dryRun);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /* ----------------------------- assignments ---------------------------- */

    @GetMapping("/{senderId}/assignments")
    public List<PlatformSenderDtos.AssignmentResponse> assignments(@PathVariable("senderId") String senderId,
                                                                   ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        return assignments.listForSender(new SenderId(senderId)).stream()
                .map(PlatformSenderDtos.AssignmentResponse::of).toList();
    }

    /** Assign the workspace, or change its label or cap. */
    @PutMapping("/{senderId}/assignments/{tenantId}")
    public PlatformSenderDtos.AssignmentResponse assign(@PathVariable("senderId") String senderId,
                                                        @PathVariable("tenantId") String tenantId,
                                                        @RequestBody PlatformSenderDtos.AssignmentRequest req,
                                                        ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        if (req == null) throw AdminApiException.badRequest("missing_body", "label required");
        return PlatformSenderDtos.AssignmentResponse.of(assignments.assign(new SenderId(senderId),
                new Ids.TenantId(tenantId), req.label(), req.dailyOutboundCap(), workspaceAccess.actorOf(exchange)));
    }

    @PostMapping("/{senderId}/assignments/{tenantId}/pause")
    public PlatformSenderDtos.AssignmentResponse pause(@PathVariable("senderId") String senderId,
                                                       @PathVariable("tenantId") String tenantId,
                                                       ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        return PlatformSenderDtos.AssignmentResponse.of(assignments.pause(new SenderId(senderId),
                new Ids.TenantId(tenantId), workspaceAccess.actorOf(exchange)));
    }

    @PostMapping("/{senderId}/assignments/{tenantId}/resume")
    public PlatformSenderDtos.AssignmentResponse resumeAssignment(@PathVariable("senderId") String senderId,
                                                                  @PathVariable("tenantId") String tenantId,
                                                                  ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        return PlatformSenderDtos.AssignmentResponse.of(assignments.resume(new SenderId(senderId),
                new Ids.TenantId(tenantId), workspaceAccess.actorOf(exchange)));
    }

    /** End the assignment (§11); {@code dryRun} lists what it would close without closing it. */
    @DeleteMapping("/{senderId}/assignments/{tenantId}")
    public PlatformSenderDtos.EndResponse end(@PathVariable("senderId") String senderId,
                                              @PathVariable("tenantId") String tenantId,
                                              @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun,
                                              ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        return PlatformSenderDtos.EndResponse.of(assignments.end(new SenderId(senderId),
                new Ids.TenantId(tenantId), workspaceAccess.actorOf(exchange), dryRun));
    }

    /** Inbound on the number that no workspace took. */
    @GetMapping("/{senderId}/unrouted")
    public List<PlatformSenderDtos.UnroutedMessage> unrouted(@PathVariable("senderId") String senderId,
                                                             @RequestParam(name = "limit", defaultValue = "50") int limit,
                                                             ServerWebExchange exchange) {
        workspaceAccess.requirePlatformAdmin(exchange);
        senders.requireForTenant(null, new SenderId(senderId));
        int n = Math.max(1, Math.min(limit, 200));
        return inbound.findTop200BySenderIdAndTenantIdIsNullOrderByReceivedAtDesc(senderId).stream()
                .limit(n).map(PlatformSenderDtos.UnroutedMessage::of).toList();
    }

    /* ------------------------------- helpers ------------------------------ */

    private PlatformSenderDtos.PlatformSenderResponse respond(WhatsAppSender s) {
        return PlatformSenderDtos.PlatformSenderResponse.of(s,
                senders.unroutedReply(s.id()).orElse(null), assignments.countLive(s.id()));
    }

    private static void validate(PlatformSenderDtos.PlatformSenderRequest req, boolean creating) {
        if (req == null) throw AdminApiException.badRequest("missing_body", "request body required");
        if (creating && (req.phoneE164() == null || req.phoneE164().isBlank())) {
            throw AdminApiException.badRequest("bad_phone", "phoneE164 must not be blank");
        }
        if (req.displayName() == null || req.displayName().isBlank()) {
            throw AdminApiException.badRequest("bad_display_name", "displayName must not be blank");
        }
        var bp = req.businessProfile();
        if (bp == null) throw AdminApiException.badRequest("missing_business_profile", "businessProfile required");
        if (isBlank(bp.legalName()) || isBlank(bp.displayName()) || isBlank(bp.contactEmail()) || isBlank(bp.vertical())) {
            throw AdminApiException.badRequest("bad_business_profile",
                    "businessProfile needs legalName, displayName, contactEmail and vertical");
        }
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
