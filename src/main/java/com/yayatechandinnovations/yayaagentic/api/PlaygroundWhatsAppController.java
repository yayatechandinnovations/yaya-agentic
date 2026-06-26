package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.InboundDispatchWorker;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth.DefaultPrincipalPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth.PhoneToPrincipalResolverRegistry;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth.PrincipalPolicyMaterializer;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundOutboundSink;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundSenderService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundSenders;
import com.yayatechandinnovations.yayaagentic.core.Ids;
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

    private final TenantGuard tenantGuard;
    private final WorkspaceAccessHelper workspaceAccess;
    private final PlaygroundSenderService playgroundSenders;
    private final InboundMessageRepository inbound;
    private final InboundDispatchWorker worker;
    private final PlaygroundOutboundSink sink;
    private final ObjectMapper json;
    private final PhoneToPrincipalResolverRegistry resolverRegistry;

    public PlaygroundWhatsAppController(TenantGuard tenantGuard,
                                        WorkspaceAccessHelper workspaceAccess,
                                        PlaygroundSenderService playgroundSenders,
                                        InboundMessageRepository inbound,
                                        InboundDispatchWorker worker,
                                        PlaygroundOutboundSink sink,
                                        ObjectMapper json,
                                        PhoneToPrincipalResolverRegistry resolverRegistry) {
        this.tenantGuard = tenantGuard;
        this.workspaceAccess = workspaceAccess;
        this.playgroundSenders = playgroundSenders;
        this.inbound = inbound;
        this.worker = worker;
        this.sink = sink;
        this.json = json;
        this.resolverRegistry = resolverRegistry;
    }

    /** Reserved key inside {@code raw_payload_json} the
     *  {@code InboundDispatchWorker} reads to stamp the playground identity
     *  override header. Never collides with Twilio fields — Twilio's are
     *  TitleCase. */
    public static final String RAW_PLAYGROUND_POLICY_OVERRIDE_KEY =
            "_playgroundDefaultPrincipalPolicyOverride";

    /** Forge a Twilio-shape inbound payload through the same dispatch worker
     *  the real webhook controller uses. Runs the worker synchronously so the
     *  Flutter test can render the bot's reply on the next outbound poll. */
    @PostMapping("/inbound")
    public ResponseEntity<InboundReceipt> sendInbound(@PathVariable("tenantId") String tenantId,
                                                      @RequestBody InboundRequest req,
                                                      ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.MEMBER);
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
        if (req.defaultPrincipalPolicyOverride() != null) {
            validatePolicyOverride(req.defaultPrincipalPolicyOverride());
            // Convert through Jackson's typed serializer so @JsonTypeInfo
            // emits the `kind` discriminator — a raw put(...) would lose it
            // because the static type at the map entry is Object.
            raw.put(RAW_PLAYGROUND_POLICY_OVERRIDE_KEY,
                    json.convertValue(req.defaultPrincipalPolicyOverride(), Map.class));
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

    /** Reset the sink for a session — used when the playground starts a
     *  fresh conversation. */
    @DeleteMapping("/outbound/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable("tenantId") String tenantId,
                                             @PathVariable("sessionId") String sessionId,
                                             ServerWebExchange exchange) {
        workspaceAccess.require(tenantId, exchange, Role.MEMBER);
        tenantGuard.requireExists(tenantId);
        sink.clearSession(new Ids.SessionId(sessionId));
        return ResponseEntity.noContent().build();
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not encode playground inbound", e);
        }
    }

    /** Reject operator-supplied overrides that don't make sense for the
     *  playground — same set of rules the materializer enforces at mint time,
     *  surfaced here so the operator sees a clear 400 instead of a server-side
     *  exception arriving as a worker failure. */
    private void validatePolicyOverride(DefaultPrincipalPolicy override) {
        switch (override) {
            case DefaultPrincipalPolicy.Anonymous a -> { /* always valid */ }
            case DefaultPrincipalPolicy.PhoneAsPrincipal p -> {
                for (String claim : p.additionalClaims().keySet()) {
                    if (PrincipalPolicyMaterializer.RESERVED_CLAIM_KEYS.contains(claim)) {
                        throw AdminApiException.badRequest("reserved_claim_in_override",
                                "additionalClaims contains reserved channel key '"
                                        + claim + "'");
                    }
                }
            }
            case DefaultPrincipalPolicy.LookupPrincipal l -> {
                boolean known = resolverRegistry.all().stream()
                        .anyMatch(r -> r.id().equals(l.resolverId()));
                if (!known) {
                    throw AdminApiException.badRequest("unknown_resolver",
                            "no phone-to-principal resolver named '"
                                    + l.resolverId() + "' is registered");
                }
            }
        }
    }

    public record InboundRequest(String text, String fromPhone, String interactiveId,
                                 String profileId, Integer profileVersion,
                                 DefaultPrincipalPolicy defaultPrincipalPolicyOverride) {}
    public record InboundReceipt(String messageSid, Instant receivedAt) {}
    public record OutboundDrain(String sessionId,
                                List<PlaygroundOutboundSink.Message> messages) {}
}
