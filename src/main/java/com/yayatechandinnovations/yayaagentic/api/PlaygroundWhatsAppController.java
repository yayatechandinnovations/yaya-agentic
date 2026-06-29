package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.InboundDispatchWorker;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.api.dto.InspectorDtos;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundOutboundSink;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundSenderService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundSenders;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundSessionRegistry;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelSessionElevationRepository;
import com.yayatechandinnovations.yayaagentic.persistence.StepUpAuditRepository;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.tenant.TenantGuard;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Playground-only inbound + outbound surface for the simulated WhatsApp
 * sender. Tenant operators (MEMBER+) use these endpoints to exercise a
 * profile through the WhatsApp channel without a real Twilio dependency.
 *
 * <p>Mounted under {@code /v1/admin/**} so the {@code OperatorAuthWebFilter}
 * populates the exchange's {@code OperatorContext} from the
 * {@code YAYA_SESSION} cookie. {@code workspaceAccess.require} then resolves
 * the operator and gates on tenant membership the same way every other
 * admin-tier controller does. Signature verification is bypassed (this is
 * not the Twilio trust edge — that lives at {@code /v1/channels/whatsapp/twilio/**}).
 *
 * <p>See {@code whatsapp-channel-design.md} §15.3.
 */
@RestController
@RequestMapping("/v1/admin/tenants/{tenantId}/whatsapp-playground")
public class PlaygroundWhatsAppController {

    /** Channel-reserved claim keys; the W-A8 pre-elevation rejects these as
     *  added scopes — they would collide with the WhatsApp authenticator's
     *  stamp and silently lose at materialization. */
    static final Set<String> RESERVED_CHANNEL_CLAIM_KEYS = Set.of(
            "phone", "channel.sender", "channel.kind", "locale", "twilioContactSid");

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final PlaygroundSenderService playgroundSenders;
    private final InboundMessageRepository inbound;
    private final InboundDispatchWorker worker;
    private final PlaygroundOutboundSink sink;
    private final ObjectMapper json;
    private final ChannelSessionElevationRepository elevations;
    private final StepUpAuditRepository stepUpAudit;
    private final PlaygroundSessionRegistry sessionRegistry;
    private final InspectorService inspector;
    private final com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthTrace authTrace;

    public PlaygroundWhatsAppController(TenantGuard tenantGuard,
                                        WorkspaceAccessHelper workspaceAccess,
                                        PlaygroundSenderService playgroundSenders,
                                        InboundMessageRepository inbound,
                                        InboundDispatchWorker worker,
                                        PlaygroundOutboundSink sink,
                                        ObjectMapper json,
                                        ChannelSessionElevationRepository elevations,
                                        StepUpAuditRepository stepUpAudit,
                                        PlaygroundSessionRegistry sessionRegistry,
                                        InspectorService inspector,
                                        com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthTrace authTrace) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.playgroundSenders = playgroundSenders;
        this.inbound = inbound;
        this.worker = worker;
        this.sink = sink;
        this.json = json;
        this.elevations = elevations;
        this.stepUpAudit = stepUpAudit;
        this.sessionRegistry = sessionRegistry;
        this.inspector = inspector;
        this.authTrace = authTrace;
    }

    /** Reserved key for the W-A8 pre-elevation marker. The worker reads it
     *  AFTER session resolution and stamps a channel_session_elevation row
     *  the StepUpScopeAuthorizer will pick up on the next tool call. */
    public static final String RAW_PLAYGROUND_PRE_ELEVATION_KEY =
            "_playgroundPreElevation";

    /** Reserved key for the client-chosen session id the Flutter playground
     *  polls {@code /outbound/{sessionId}} with. The worker reads it after
     *  session resolution and registers a {@code PlaygroundSessionRegistry}
     *  mapping so the engine's internal UUID session id routes outbound to a
     *  sink bucket the client can actually drain. */
    public static final String RAW_PLAYGROUND_CLIENT_SESSION_KEY =
            "_playgroundClientSessionId";

    /** Forge a Twilio-shape inbound payload through the same dispatch worker
     *  the real webhook controller uses. Runs the worker synchronously so the
     *  Flutter test can render the bot's reply on the next outbound poll. */
    @PostMapping("/inbound")
    public ResponseEntity<InboundReceipt> sendInbound(@PathVariable("tenantId") String tenantId,
                                                      @RequestBody InboundRequest req,
                                                      ServerWebExchange exchange) {
        // Default require — every playground inbound is MEMBER+. Pre-elevation
        // demands ADMIN; we escalate the check only when present.
        workspaceAccess.require(tenantId, exchange, Role.MEMBER);
        if (req != null && req.preElevation() != null) {
            workspaceAccess.require(tenantId, exchange, Role.ADMIN);
        }
        tenantGuard.requireExists(tenantId);
        if (req == null || req.text() == null) {
            throw AdminApiException.badRequest("missing_body", "text required");
        }
        Ids.TenantId tenant = new Ids.TenantId(tenantId);
        SenderId senderId = playgroundSenders.ensureForTenant(tenant);
        if (req.profileId() != null && !req.profileId().isBlank()) {
            Ids.ProfileId profile = new Ids.ProfileId(req.profileId(),
                    req.profileVersion() == null ? 1 : req.profileVersion());
            playgroundSenders.ensureBinding(tenant, senderId, profile);
        }

        String fromPhone = req.fromPhone() == null || req.fromPhone().isBlank()
                ? "+15558675309"
                : req.fromPhone();
        String messageSid = "SMplay_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20);

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("MessageSid", messageSid);
        raw.put("From", "whatsapp:" + fromPhone);
        raw.put("To", "whatsapp:" + PlaygroundSenders.phoneForTenant(new Ids.TenantId(tenantId)));
        raw.put("Body", req.text());
        raw.put("NumMedia", "0");
        // Stamp interactive selection hints when the playground UI sends a
        // button/list tap; the worker normalizes the same way Twilio would.
        if (req.interactiveId() != null && !req.interactiveId().isBlank()) {
            raw.put("ButtonText", req.text());
            raw.put("ButtonPayload", req.interactiveId());
        }
        if (req.preElevation() != null) {
            validatePreElevation(req.preElevation());
            raw.put(RAW_PLAYGROUND_PRE_ELEVATION_KEY,
                    json.convertValue(req.preElevation(), Map.class));
        }
        if (req.sessionId() != null && !req.sessionId().isBlank()) {
            raw.put(RAW_PLAYGROUND_CLIENT_SESSION_KEY, req.sessionId());
        }

        InboundMessageEntity row = new InboundMessageEntity(
                messageSid, tenantId, senderId.value(), fromPhone, 0, writeJson(raw));
        try {
            inbound.save(row);
        } catch (DataIntegrityViolationException dup) {
            // Same MessageSid twice — refuse rather than silently dedup;
            // the playground caller really shouldn't hit this.
            throw AdminApiException.conflict("duplicate_message_sid",
                    "playground messages must use a fresh MessageSid");
        }
        // Submit to the worker pool (NOT the reactive event loop) — the
        // worker's downstream stack does blocking reactor calls into the
        // engine + outbound dispatcher. The Flutter playground polls
        // /outbound for the bot's reply.
        worker.schedule(messageSid);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new InboundReceipt(messageSid, Instant.now()));
    }

    /** Drain new outbound messages for a session — long-polled by the
     *  Flutter playground. Returns immediately with an empty list when no
     *  new messages; the Flutter client polls on a short cadence. */
    @GetMapping("/outbound/{sessionId}")
    public OutboundDrain pollOutbound(@PathVariable("tenantId") String tenantId,
                                      @PathVariable("sessionId") String sessionId,
                                      @RequestParam(value = "after", required = false) String after,
                                      ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.MEMBER);
        tenantGuard.requireExists(tenantId);
        List<PlaygroundOutboundSink.Message> messages = sink.drain(
                new Ids.SessionId(sessionId), after);
        return new OutboundDrain(sessionId, messages);
    }

    /** Inspector snapshot for a playground session — the WhatsApp analogue of
     *  {@code /v1/sessions/{id}/inspector}. The UI polls by its client-chosen
     *  session id; we map it to the engine's internal UUID via the {@link
     *  PlaygroundSessionRegistry} so the inspector reads the right session
     *  state (intent, working memory, last prompt, denial, retrieval, tool
     *  activity). Returns an empty snapshot until the worker has resolved the
     *  session and registered the mapping. */
    @GetMapping("/outbound/{sessionId}/inspector")
    public InspectorDtos.InspectorSnapshot inspect(@PathVariable("tenantId") String tenantId,
                                                   @PathVariable("sessionId") String sessionId,
                                                   ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.MEMBER);
        tenantGuard.requireExists(tenantId);
        Ids.SessionId engineId = sessionRegistry.engineFor(sessionId)
                .orElseGet(() -> new Ids.SessionId(sessionId));
        return inspector.snapshot(engineId);
    }

    /** Reset the sink for a session — used when the playground starts a
     *  fresh conversation. */
    @DeleteMapping("/outbound/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable("tenantId") String tenantId,
                                             @PathVariable("sessionId") String sessionId,
                                             ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.MEMBER);
        tenantGuard.requireExists(tenantId);
        sink.clearSession(new Ids.SessionId(sessionId));
        // Drop the auth-flow trace for the engine session this client id maps
        // to, so a reset starts the inspector clean (design §13.1).
        sessionRegistry.engineFor(sessionId).ifPresent(authTrace::wipe);
        return ResponseEntity.noContent().build();
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not encode playground inbound", e);
        }
    }

    /** Reject pre-elevations that violate the security invariants — the
     *  channel-reserved scope guard, the audience requirement, the no-empty-
     *  subject rule. The worker enforces a matching set when it stamps the
     *  elevation row; this is the loud, operator-facing version. */
    private void validatePreElevation(PreElevation pe) {
        if (pe.subject() == null || pe.subject().isBlank()) {
            throw AdminApiException.badRequest("missing_pre_elevation_subject",
                    "preElevation.subject is required");
        }
        if (pe.audience() == null || pe.audience().isBlank()) {
            throw AdminApiException.badRequest("missing_pre_elevation_audience",
                    "preElevation.audience is required");
        }
        if (pe.addedScopes() == null || pe.addedScopes().isEmpty()) {
            throw AdminApiException.badRequest("missing_pre_elevation_scopes",
                    "preElevation.addedScopes must contain at least one scope");
        }
        for (String scope : pe.addedScopes()) {
            if (RESERVED_CHANNEL_CLAIM_KEYS.contains(scope)) {
                throw AdminApiException.badRequest("reserved_scope_in_pre_elevation",
                        "preElevation.addedScopes contains a reserved channel key '"
                                + scope + "'");
            }
        }
    }

    public record InboundRequest(String text, String fromPhone, String interactiveId,
                                 String profileId, Integer profileVersion,
                                 PreElevation preElevation, String sessionId) {}

    /**
     * Playground-only shortcut that skips the OTP / deep-link dance. The
     * operator declares "this simulated session is already verified for
     * {@code audience} with these scopes" — the worker stamps a
     * {@code channel_session_elevation} row before the engine runs.
     *
     * @param subject     elevated subject id stamped on the elevation row
     * @param audience    audience string the elevation grants
     *                    (matches StepUpAuthorizer rule.audience())
     * @param addedScopes scopes merged into the principal — typically
     *                    {@code [stepup:<audience>]}
     * @param ttlSeconds  how long the elevation lasts; capped at 1 hour
     */
    public record PreElevation(String subject, String audience,
                               List<String> addedScopes, Integer ttlSeconds) {}
    public record InboundReceipt(String messageSid, Instant receivedAt) {}
    public record OutboundDrain(String sessionId,
                                List<PlaygroundOutboundSink.Message> messages) {}
}
