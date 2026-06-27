package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpCoordinator;
import com.yayatechandinnovations.yayaagentic.channel.ChannelKind;
import com.yayatechandinnovations.yayaagentic.channel.MediaTranscriber;
import com.yayatechandinnovations.yayaagentic.channel.SyntheticToolIds;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBinding;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBindingService;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.WhisperMediaTranscriber;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityKind;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityMemory;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityRef;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityScope;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.StartSessionResult;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Background processor for inbound WhatsApp messages. The webhook controller
 * has already validated the signature, deduped, and persisted to
 * {@code inbound_messages}; this worker:
 *
 * <ol>
 *   <li>Looks up the sender + active binding</li>
 *   <li>Resolves session continuity per the binding's session policy</li>
 *   <li>Transcribes any inbound audio (W4 §9.5)</li>
 *   <li>Captures end-user phone / channel / locale into EntityMemory using
 *       the {@code SyntheticToolIds.CHANNEL_WHATSAPP_INBOUND} reserved
 *       producer id (W4 §12.1)</li>
 *   <li>Calls {@code engine.start} or {@code engine.send} as appropriate</li>
 *   <li>Stamps {@code processed_at} or {@code processing_error} on the
 *       {@code inbound_messages} row</li>
 * </ol>
 *
 * <p>The engine's outbound flux is consumed but not delivered here — W5's
 * outbound dispatcher subscribes via a separate seam. For now we just drain
 * the flux so the engine completes its turn.
 */
@Component
public class InboundDispatchWorker {

    private static final Logger log = LoggerFactory.getLogger(InboundDispatchWorker.class);

    private final ThreadPoolTaskExecutor executor;
    private final InboundMessageRepository inbound;
    private final WhatsAppSenderService senders;
    private final ChannelBindingService bindings;
    private final ChannelSessionService sessions;
    private final ConversationEngine engine;
    private final EntityMemory entityMemory;
    private final ObjectMapper json;
    private final Optional<MediaTranscriber> transcriber;
    private final OutboundDispatcher outboundDispatcher;
    private final OptOutService optOuts;
    private final ChannelTracing tracing;
    private final int maxAudioMinutes;
    private final long maxAudioBytes;
    private final StepUpCoordinator stepUp;
    private final com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthIntegrator
            authIntegrator;
    private final com.yayatechandinnovations.yayaagentic.channel.auth.SessionAuthTokenStore
            authTokenStore;

    public InboundDispatchWorker(
            @Qualifier("channelDispatchExecutor") ThreadPoolTaskExecutor executor,
            InboundMessageRepository inbound,
            WhatsAppSenderService senders,
            ChannelBindingService bindings,
            ChannelSessionService sessions,
            ConversationEngine engine,
            EntityMemory entityMemory,
            ObjectMapper json,
            Optional<MediaTranscriber> transcriber,
            OutboundDispatcher outboundDispatcher,
            OptOutService optOuts,
            ChannelTracing tracing,
            @Value("${yaya.agentic.channels.whatsapp.transcription.max-minutes:5}") int maxAudioMinutes,
            @Value("${yaya.agentic.channels.whatsapp.transcription.max-bytes:16777216}") long maxAudioBytes,
            StepUpCoordinator stepUp,
            com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthIntegrator authIntegrator,
            com.yayatechandinnovations.yayaagentic.channel.auth.SessionAuthTokenStore authTokenStore) {
        this.executor = executor;
        this.inbound = inbound;
        this.senders = senders;
        this.bindings = bindings;
        this.sessions = sessions;
        this.engine = engine;
        this.entityMemory = entityMemory;
        this.json = json;
        this.transcriber = transcriber;
        this.outboundDispatcher = outboundDispatcher;
        this.optOuts = optOuts;
        this.tracing = tracing;
        this.maxAudioMinutes = maxAudioMinutes;
        this.maxAudioBytes = maxAudioBytes;
        this.stepUp = stepUp;
        this.authIntegrator = authIntegrator;
        this.authTokenStore = authTokenStore;
    }

    /**
     * Submit a row to the worker pool. Returns immediately — the actual
     * engine round-trip happens on a worker thread. Failure to enqueue
     * (pool saturation) is logged and surfaced via
     * {@code processing_error="enqueue_rejected"} on a later sweep.
     */
    public void schedule(String messageSid) {
        try {
            executor.execute(() -> {
                try {
                    process(messageSid);
                } catch (RuntimeException e) {
                    log.warn("InboundDispatchWorker failed for {}: {}", messageSid, e.getMessage(), e);
                    markFailed(messageSid, e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("InboundDispatchWorker enqueue rejected for {}: {}", messageSid, e.getMessage());
            markFailed(messageSid, "enqueue_rejected: " + e.getMessage());
        }
    }

    /** Test-friendly synchronous entry point — same contract as
     *  {@link #schedule} but runs on the caller thread. */
    public void processSync(String messageSid) {
        try {
            process(messageSid);
        } catch (RuntimeException e) {
            markFailed(messageSid, e.getMessage());
            throw e;
        }
    }

    @Transactional
    protected void process(String messageSid) {
        tracing.observe("channel.whatsapp.dispatch",
                () -> processInternal(messageSid),
                "channel", "whatsapp", "message_sid", messageSid);
    }

    private void processInternal(String messageSid) {
        InboundMessageEntity row = inbound.findById(messageSid).orElse(null);
        if (row == null) {
            log.warn("InboundDispatchWorker: row {} disappeared before processing", messageSid);
            return;
        }
        if (row.getProcessedAt() != null) {
            return;     // already processed (idempotent)
        }

        // 1. sender + binding
        WhatsAppSender sender = senders.requireForTenant(
                new Ids.TenantId(row.getTenantId()),
                new SenderId(row.getSenderId()));
        if (!sender.status().acceptsInbound()) {
            markFailed(messageSid, "sender_not_accepting_inbound: " + sender.status());
            return;
        }
        if (!sender.status().acceptsOutbound()) {
            // PENDING_REVIEW / SUSPENDED — ack-only path. Don't burn an LLM
            // turn; just stamp processed_at so the rescue sweeper doesn't
            // come back to it.
            row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
            inbound.save(row);
            return;
        }

        Map<String, Object> raw = readRaw(row.getRawPayloadJson());
        String body = (String) raw.getOrDefault("Body", "");

        // 1a. §11.2 — STOP/UNSUB/CANCEL/QUIT (or START/UNSTOP/SUBSCRIBE)
        // short-circuit the engine. Confirmation copy is fixed by Meta
        // policy; never run an LLM round-trip for these.
        OptOutService.Classification optClass = optOuts.classify(body);
        if (optClass == OptOutService.Classification.STOP) {
            optOuts.optOut(new SenderId(row.getSenderId()),
                    row.getEndUserPhone(), "user_stop");
            try {
                outboundDispatcher.dispatchGreeting(
                        new Ids.TenantId(row.getTenantId()),
                        new SenderId(row.getSenderId()),
                        row.getEndUserPhone(),
                        new Ids.SessionId(java.util.UUID.randomUUID().toString()),
                        OptOutService.STOP_CONFIRMATION,
                        java.util.List.of());
            } catch (RuntimeException ignored) {}
            row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
            row.setBodyRedacted(body);
            inbound.save(row);
            return;
        }
        if (optClass == OptOutService.Classification.START) {
            optOuts.resubscribe(new SenderId(row.getSenderId()), row.getEndUserPhone());
            try {
                outboundDispatcher.dispatchGreeting(
                        new Ids.TenantId(row.getTenantId()),
                        new SenderId(row.getSenderId()),
                        row.getEndUserPhone(),
                        new Ids.SessionId(java.util.UUID.randomUUID().toString()),
                        OptOutService.START_CONFIRMATION,
                        java.util.List.of());
            } catch (RuntimeException ignored) {}
            row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
            row.setBodyRedacted(body);
            inbound.save(row);
            return;
        }
        // §11.2 — if the user already opted out, ack without doing anything
        // (Meta policy forbids contacting them). They must START first.
        if (optOuts.isOptedOut(new SenderId(row.getSenderId()), row.getEndUserPhone())) {
            row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
            row.setProcessingError("recipient_opted_out");
            inbound.save(row);
            return;
        }

        // 1b. §10.3 — when the user tapped a button or selected a list row,
        // Twilio supplies ButtonText / ListTitle alongside Body. Normalize
        // the inbound to look like the user typed the label, and stamp the
        // structured ID into hints so a profile can short-circuit on it.
        InteractiveSelection sel = extractInteractiveSelection(raw);
        if (sel != null) {
            body = sel.label;
        }

        // 2. transcribe audio + mutate body if needed
        List<MediaDescriptor> media = extractMedia(raw);
        TranscriptionOutcome transcription = transcribeIfPresent(row, media);
        if (transcription.text != null && !transcription.text.isBlank()) {
            body = body.isBlank()
                    ? transcription.text
                    : body + "\n\n[voice note: " + transcription.text + "]";
            row.setMediaTranscriptsJson(transcription.transcriptsJson);
            inbound.save(row);
        }

        // 3. binding resolution — supplies the session policy + profile id
        var endpoint = new com.yayatechandinnovations.yayaagentic.channel.ChannelEndpointRef
                .WhatsAppSenderRef(new SenderId(row.getSenderId()));
        // The principal is minted by the authenticator chain when the engine
        // start runs; before that, the binding lookup just needs the routing
        // hints. We pass a null principal — only EndUserClaim rules need it.
        Map<String, Object> hints = buildHints(row, sender, body, transcription);
        Optional<ChannelBinding> bindingOpt = bindings.findFor(
                new Ids.TenantId(row.getTenantId()), endpoint, null, hints);
        if (bindingOpt.isEmpty()) {
            markFailed(messageSid, "no_binding_matched");
            return;
        }
        ChannelBinding binding = bindingOpt.get();
        SessionPolicy policy = binding.sessionPolicy();

        // 4. session continuity
        ChannelSessionService.Outcome cont = sessions.openOrReuse(
                new Ids.TenantId(row.getTenantId()),
                new SenderId(row.getSenderId()),
                row.getEndUserPhone(),
                body,
                policy);

        // Resolved engine session id — either reused or freshly minted
        Ids.SessionId sessionId;
        boolean fresh;
        if (cont instanceof ChannelSessionService.Outcome.Reuse reuse) {
            sessionId = reuse.existing();
            fresh = false;
        } else {
            sessionId = null;       // filled below by engine.start
            fresh = true;
        }
        boolean consumedKeyword =
                cont instanceof ChannelSessionService.Outcome.Reuse r ? r.consumedKeyword() :
                cont instanceof ChannelSessionService.Outcome.Reopen ro ? ro.consumedKeyword() :
                ((ChannelSessionService.Outcome.Open) cont).consumedKeyword();

        // §9.2: when the body is exactly a new-session keyword, the engine
        // never sees it. The next inbound resumes the conversation.
        String userText = consumedKeyword ? "" : body;

        // 5. build inputs for the engine
        StartConversationRequest startReq = new StartConversationRequest(
                new Ids.TenantId(row.getTenantId()),
                Optional.of(binding.profile()),
                ChannelKind.WhatsApp.class.getSimpleName().toLowerCase(),
                hints);

        Map<String, Object> rawForAuth = readRaw(row.getRawPayloadJson());
        AuthContext baseAuth = new AuthContext(
                new Ids.TenantId(row.getTenantId()),
                authHeaders(row, sender, transcription.detectedLanguage, rawForAuth),
                Optional.empty());

        // 5b. binding-driven identity resolution (§9). For bindings with
        //     auth_resolver_id, fire the resolver, verify the returned JWT
        //     via the existing chain, stamp channel claims, and present the
        //     verified Principal to the engine via alreadyVerified. For
        //     bindings without one (FAQ mode), this is a no-op pass-through.
        com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthIntegrator.Resolved
                authResolved = authIntegrator.resolve(
                binding, baseAuth, sessionId,
                new SenderId(row.getSenderId()),
                sender.phoneE164(),
                row.getEndUserPhone());
        if (authResolved.decision()
                == com.yayatechandinnovations.yayaagentic.channel.auth
                        .ChannelAuthIntegrator.Resolved.Decision.REJECT) {
            // Binding's on_unresolved=REJECT — the verified caller phone
            // doesn't map to a known tenant identity. Drop the message
            // (same shape as no_binding_matched: no user-facing reply).
            markFailed(messageSid, "auth_resolver_rejected");
            return;
        }
        AuthContext auth = authResolved.authContextForEngine(baseAuth);

        // 6. release any parked outbound — the inbound just reopened the
        //    24h service window, so anything queued can fire now.
        outboundDispatcher.releasePending(
                new Ids.TenantId(row.getTenantId()),
                new SenderId(row.getSenderId()),
                row.getEndUserPhone());

        // 7. run the engine — start when fresh, send when reused
        if (fresh) {
            StartSessionResult result = engine.start(startReq, auth);
            sessionId = result.session().id();
            sessions.register(
                    new Ids.TenantId(row.getTenantId()),
                    new SenderId(row.getSenderId()),
                    row.getEndUserPhone(),
                    sessionId);
            // §9 step 3e — persist the resolver-minted JWT on the freshly
            // created session row so the engine can forward it to tool calls
            // and refresh it on tool-level 401 (PR 6c).
            Ids.SessionId freshId = sessionId;
            authResolved.jwt().ifPresent(jwt ->
                    authTokenStore.persist(freshId, jwt));
            captureEntities(sessionId, sender, row, transcription, policy);
            // W-A8 — playground pre-elevation: stamp the elevation row BEFORE
            // engine.send so the very first tool call sees it via the
            // StepUpScopeAuthorizer's elevation-merge path.
            applyPlaygroundPreElevationIfPresent(rawForAuth, row, sessionId);
            outboundDispatcher.dispatchGreeting(
                    new Ids.TenantId(row.getTenantId()),
                    new SenderId(row.getSenderId()),
                    row.getEndUserPhone(),
                    sessionId,
                    result.greeting(),
                    result.quickReplies());
            if (!userText.isBlank()) {
                outboundDispatcher.dispatchTurn(
                        new Ids.TenantId(row.getTenantId()),
                        new SenderId(row.getSenderId()),
                        row.getEndUserPhone(),
                        sessionId,
                        engine.send(sessionId, new UserMessage(userText, Map.of()), auth));
            }
        } else {
            // §9 step 3e on continuation — refresh the session row's JWT
            // copy if the integrator produced a (possibly cache-hit) token.
            Ids.SessionId reusedId = sessionId;
            authResolved.jwt().ifPresent(jwt ->
                    authTokenStore.persist(reusedId, jwt));
            captureEntities(sessionId, sender, row, transcription, policy);
            applyPlaygroundPreElevationIfPresent(rawForAuth, row, sessionId);
            if (!userText.isBlank()) {
                // §6.6 — when an OTP-shaped pending exists for this session,
                // interpret the inbound as the user's verification code BEFORE
                // routing to the engine. The LLM never sees the OTP digits.
                if (stepUp.hasOpenOtpPending(sessionId)
                        && handleStepUpVerify(row, sessionId, userText)) {
                    // Step-up dispatcher already sent the response; skip engine.
                } else {
                    outboundDispatcher.dispatchTurn(
                            new Ids.TenantId(row.getTenantId()),
                            new SenderId(row.getSenderId()),
                            row.getEndUserPhone(),
                            sessionId,
                            engine.send(sessionId, new UserMessage(userText, Map.of()), auth));
                }
            }
        }

        row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
        row.setBodyRedacted(redact(body, row.getEndUserPhone()));
        inbound.save(row);
    }

    private TranscriptionOutcome transcribeIfPresent(InboundMessageEntity row, List<MediaDescriptor> media) {
        if (media.isEmpty()) return TranscriptionOutcome.empty();
        boolean enabled = transcriber.isPresent();
        if (!enabled) return TranscriptionOutcome.empty();

        List<Map<String, Object>> transcripts = new ArrayList<>();
        StringBuilder combined = new StringBuilder();
        String detectedLanguage = null;
        for (MediaDescriptor m : media) {
            if (!transcriber.get().supportedContentTypes().contains(m.contentType())) continue;
            try {
                MediaTranscriber.TranscriptionResult result = transcriber.get().transcribe(
                        new MediaTranscriber.InboundMediaRef(URI.create(m.mediaUrl()), m.contentType(), m.size()),
                        new MediaTranscriber.TranscriptionContext(
                                row.getTenantId(), row.getSenderId(), row.getMessageSid(),
                                Duration.ofMinutes(maxAudioMinutes), maxAudioBytes,
                                null));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("mediaUrl", m.mediaUrl());
                entry.put("contentType", m.contentType());
                entry.put("transcript", result.text());
                entry.put("detectedLanguage", result.detectedLanguage());
                entry.put("modelId", result.modelId());
                entry.put("durationSeconds", result.audioDuration() == null ? 0
                        : result.audioDuration().toSeconds());
                transcripts.add(entry);
                if (!combined.isEmpty()) combined.append(" ");
                combined.append(result.text());
                if (detectedLanguage == null) detectedLanguage = result.detectedLanguage();
            } catch (WhisperMediaTranscriber.TranscriptionRejectedException e) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("mediaUrl", m.mediaUrl());
                entry.put("contentType", m.contentType());
                entry.put("error", e.code() + ": " + e.getMessage());
                transcripts.add(entry);
            } catch (RuntimeException e) {
                log.warn("Transcriber failed for {}: {}", m.mediaUrl(), e.getMessage());
            }
        }
        String json = writeJson(Map.of("items", transcripts));
        return new TranscriptionOutcome(combined.toString(), detectedLanguage, json);
    }

    private void captureEntities(Ids.SessionId session, WhatsAppSender sender,
                                 InboundMessageEntity row,
                                 TranscriptionOutcome transcription,
                                 SessionPolicy policy) {
        Map<String, Object> baseMeta = Map.of("pii", true);
        if (policy.exposeEndUserPhoneToProfile()) {
            entityMemory.capture(session,
                    entity("endUserPhone", row.getEndUserPhone(), baseMeta));
        }
        entityMemory.capture(session,
                entity("endUserChannel", "whatsapp", Map.of()));
        String locale = transcription.detectedLanguage == null
                ? sender.defaultLocale()
                : transcription.detectedLanguage;
        if (locale != null && !locale.isBlank()) {
            entityMemory.capture(session, entity("endUserLocale", locale, Map.of()));
        }
    }

    private EntityRef entity(String name, Object value, Map<String, Object> meta) {
        return new EntityRef(
                name,
                value,
                EntityScope.SESSION,
                EntityKind.SINGLETON,
                /* visibleToLlm */ true,
                SyntheticToolIds.CHANNEL_WHATSAPP_INBOUND,
                java.time.Instant.now(),
                meta);
    }

    private Map<String, Object> buildHints(InboundMessageEntity row,
                                           WhatsAppSender sender,
                                           String body,
                                           TranscriptionOutcome t) {
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("channel.kind", "whatsapp");
        hints.put("channel.senderId", row.getSenderId());
        hints.put("channel.endUser", row.getEndUserPhone());
        hints.put("channel.locale",
                t.detectedLanguage == null ? sender.defaultLocale() : t.detectedLanguage);
        hints.put("channel.bsp", "twilio");
        hints.put("channel.inbound.messageSid", row.getMessageSid());
        hints.put("channel.inbound.body", body);

        Map<String, Object> raw = readRaw(row.getRawPayloadJson());
        InteractiveSelection sel = extractInteractiveSelection(raw);
        if (sel != null) {
            hints.put("channel.inbound.interactiveId", sel.id);
            hints.put("channel.inbound.interactiveKind", sel.kind);
        }
        return hints;
    }

    /** §10.3 — extract the structured selection from a Twilio webhook. */
    private static InteractiveSelection extractInteractiveSelection(Map<String, Object> raw) {
        String buttonText  = (String) raw.get("ButtonText");
        String buttonPayload = (String) raw.get("ButtonPayload");
        String listId      = (String) raw.get("ListId");
        String listTitle   = (String) raw.get("ListTitle");
        if (buttonText != null && !buttonText.isBlank()) {
            return new InteractiveSelection(
                    buttonPayload != null && !buttonPayload.isBlank() ? buttonPayload : buttonText,
                    buttonText,
                    "button");
        }
        if (listId != null && !listId.isBlank()) {
            return new InteractiveSelection(listId, listTitle, "list_row");
        }
        return null;
    }

    private record InteractiveSelection(String id, String label, String kind) {}

    /**
     * W-A8 — playground pre-elevation: the controller stashed an elevation
     * spec in {@code raw_payload_json._playgroundPreElevation}. After session
     * resolution we stamp the elevation row so the very first tool call sees
     * it via the StepUpScopeAuthorizer's elevation-merge path. No-op when the
     * marker isn't present.
     */
    @SuppressWarnings("unchecked")
    private void applyPlaygroundPreElevationIfPresent(Map<String, Object> raw,
                                                       InboundMessageEntity row,
                                                       Ids.SessionId sessionId) {
        if (raw == null) return;
        Object marker = raw.get(
                com.yayatechandinnovations.yayaagentic.api.PlaygroundWhatsAppController
                        .RAW_PLAYGROUND_PRE_ELEVATION_KEY);
        if (!(marker instanceof Map)) return;
        Map<String, Object> spec = (Map<String, Object>) marker;
        String subject = (String) spec.get("subject");
        String audience = (String) spec.get("audience");
        List<String> scopes = (List<String>) spec.get("addedScopes");
        Object ttlObj = spec.get("ttlSeconds");
        Duration ttl;
        if (ttlObj instanceof Number n) {
            ttl = Duration.ofSeconds(Math.min(n.longValue(), 3600));
        } else {
            ttl = Duration.ofHours(1);
        }
        if (subject == null || subject.isBlank()
                || audience == null || audience.isBlank()
                || scopes == null || scopes.isEmpty()) {
            log.warn("playground pre-elevation marker malformed; skipping (sessionId={})",
                    sessionId.value());
            return;
        }
        stepUp.recordPlaygroundPreElevation(
                new Ids.TenantId(row.getTenantId()),
                sessionId,
                subject,
                audience,
                new LinkedHashSet<>(scopes),
                Map.of(
                        "stepup.audience", audience,
                        "stepup.method", "playground_pre_elevation"),
                ttl);
        log.info("playground pre-elevation applied (sessionId={} audience={})",
                sessionId.value(), audience);
    }

    /**
     * Intercept the inbound as a step-up OTP. Returns {@code true} when the
     * inbound was consumed by the verification flow (and an appropriate
     * outbound was dispatched); {@code false} when the body didn't match an
     * OTP shape, in which case the worker falls through to the engine.
     *
     * <p>See {@code whatsapp-channel-auth-design.md} §6.4 + §6.6.
     */
    private boolean handleStepUpVerify(InboundMessageEntity row,
                                       Ids.SessionId sessionId,
                                       String userText) {
        String trimmed = userText.trim();
        if (!trimmed.matches("\\d{6}")) {
            // Not OTP-shaped; the user typed something else (cancellation,
            // unrelated message). Route to the engine — the bot can decide
            // whether to remind them about the verification or pivot.
            return false;
        }
        Ids.TenantId tenant = new Ids.TenantId(row.getTenantId());
        StepUpCoordinator.VerifyOutcome outcome = stepUp.verifyOtp(
                new StepUpCoordinator.VerifyContext(
                        tenant,
                        sessionId,
                        "whatsapp:" + row.getEndUserPhone(),
                        trimmed,
                        Duration.ofHours(1)));

        SenderId senderId = new SenderId(row.getSenderId());
        String endUserPhone = row.getEndUserPhone();
        String text = switch (outcome) {
            case StepUpCoordinator.VerifyOutcome.NoPending p -> null;
            case StepUpCoordinator.VerifyOutcome.Verified v ->
                    "Verified! What would you like to do?";
            case StepUpCoordinator.VerifyOutcome.WrongCode w -> w.attemptsRemaining() == 1
                    ? "That code didn't match. You have 1 attempt left."
                    : "That code didn't match. You have " + w.attemptsRemaining()
                            + " attempts left.";
            case StepUpCoordinator.VerifyOutcome.Exhausted e ->
                    "Too many incorrect attempts. Please try the original request again "
                            + "to get a fresh code.";
        };
        if (text == null) return false;
        outboundDispatcher.dispatchGreeting(tenant, senderId, endUserPhone, sessionId,
                text, List.of());
        return true;
    }

    private Map<String, String> authHeaders(InboundMessageEntity row, WhatsAppSender sender,
                                            String detectedLanguage,
                                            Map<String, Object> raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(WhatsAppPhoneAuthenticator.HEADER_END_USER, row.getEndUserPhone());
        headers.put(WhatsAppPhoneAuthenticator.HEADER_SENDER_ID, row.getSenderId());
        headers.put(WhatsAppPhoneAuthenticator.HEADER_LOCALE,
                detectedLanguage == null ? sender.defaultLocale() : detectedLanguage);
        return headers;
    }

    /** Minimum: redact the end-user's own phone if it appeared in the body. */
    private String redact(String body, String phone) {
        if (body == null) return null;
        if (phone == null) return body;
        return body.replace(phone, "+<redacted>");
    }

    private List<MediaDescriptor> extractMedia(Map<String, Object> raw) {
        // Twilio's WhatsApp webhook puts NumMedia + MediaUrl0..N + MediaContentType0..N
        Object numMedia = raw.get("NumMedia");
        int n = parseInt(numMedia, 0);
        if (n <= 0) return List.of();
        List<MediaDescriptor> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String url = (String) raw.get("MediaUrl" + i);
            String type = (String) raw.get("MediaContentType" + i);
            if (url == null || type == null) continue;
            out.add(new MediaDescriptor(url, type, 0L));
        }
        return out;
    }

    private static int parseInt(Object v, int fallback) {
        if (v == null) return fallback;
        try { return Integer.parseInt(v.toString()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private Map<String, Object> readRaw(String s) {
        try {
            return json.readValue(s, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not decode raw_payload_json", e);
        }
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not encode media_transcripts_json", e);
        }
    }

    @Transactional
    protected void markFailed(String messageSid, String error) {
        inbound.findById(messageSid).ifPresent(row -> {
            row.setProcessingError(error);
            row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
            inbound.save(row);
        });
    }

    record MediaDescriptor(String mediaUrl, String contentType, long size) {}

    record TranscriptionOutcome(String text, String detectedLanguage, String transcriptsJson) {
        static TranscriptionOutcome empty() {
            return new TranscriptionOutcome(null, null, null);
        }
    }
}
