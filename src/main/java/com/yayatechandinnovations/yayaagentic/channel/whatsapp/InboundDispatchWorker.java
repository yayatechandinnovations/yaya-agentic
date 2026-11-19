package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.core.TurnChannel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpCoordinator;
import com.yayatechandinnovations.yayaagentic.channel.ChannelKind;
import com.yayatechandinnovations.yayaagentic.channel.InboundMediaClient;
import com.yayatechandinnovations.yayaagentic.channel.chat.media.ChatAttachmentService;
import com.yayatechandinnovations.yayaagentic.channel.chat.media.ChatMessageComposer;
import com.yayatechandinnovations.yayaagentic.channel.chat.media.ImageCodes;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.usage.UsageMeter;
import reactor.core.publisher.Flux;
import com.yayatechandinnovations.yayaagentic.channel.MediaTranscriber;
import com.yayatechandinnovations.yayaagentic.channel.ProviderCredsResolver;
import com.yayatechandinnovations.yayaagentic.channel.SecretInboundRedaction;
import com.yayatechandinnovations.yayaagentic.channel.SyntheticToolIds;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBinding;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBindingService;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioCreds;
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
    private final ProviderCredsResolver credsResolver;
    private final InboundImageLoader imageLoader;
    private final OutboundDispatcher outboundDispatcher;
    private final OptOutService optOuts;
    private final ConversationHoldService holds;
    private final ChannelTracing tracing;
    private final com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground
            .PlaygroundSessionRegistry playgroundSessions;
    private final int maxAudioSeconds;
    private final long maxAudioBytes;
    // Media (assistant-channels design §3.3): optional so the hand-built worker
    // tests keep compiling; absent = PDFs are not kept and media is not metered.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ChatAttachmentService chatAttachments;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InboundMediaClient mediaClient;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UsageMeter usageMeter;

    void setChatAttachments(ChatAttachmentService chatAttachments) { this.chatAttachments = chatAttachments; }
    void setMediaClient(InboundMediaClient mediaClient) { this.mediaClient = mediaClient; }
    void setUsageMeter(UsageMeter usageMeter) { this.usageMeter = usageMeter; }
    private final StepUpCoordinator stepUp;
    private final com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthIntegrator
            authIntegrator;
    private final com.yayatechandinnovations.yayaagentic.channel.auth.SessionAuthTokenStore
            authTokenStore;
    private final com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthTrace authTrace;
    private final OfferedOptionsStore offeredOptions;
    // Optional so the many hand-built worker tests keep compiling (§6.6).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OutreachContextSeeder contextSeeder;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.yayatechandinnovations.yayaagentic.engine.handoff.ChannelHandoffService handoffs;

    // Deterministic answers that never wake the engine (ticket-share design §8
    // P4/P5); optional for the same reason as the collaborators above.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords.KeywordActionService keywordActions;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords.KeywordForwarder keywordForwarder;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UnresolvedAutoReplyService autoReplies;

    void setContextSeeder(OutreachContextSeeder seeder) { this.contextSeeder = seeder; }

    void setKeywordActions(com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords.KeywordActionService s,
                           com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords.KeywordForwarder f) {
        this.keywordActions = s;
        this.keywordForwarder = f;
    }

    void setAutoReplies(UnresolvedAutoReplyService service) { this.autoReplies = service; }

    void setHandoffs(com.yayatechandinnovations.yayaagentic.engine.handoff.ChannelHandoffService service) {
        this.handoffs = service;
    }

    /** Button payloads of the greeting's offer to continue the app conversation (C5). */
    static final String HANDOFF_PREFIX = "handoff:";
    static final String FRESH_ID = "fresh";
    /** WhatsApp caps a button title at 20 characters; longer labels fall back to a list. */
    static final String HANDOFF_LABEL = "Seguir con la app";
    static final String FRESH_LABEL = "Empezar de cero";
    static final String HANDOFF_OFFER_LINE = "¿Seguimos con lo que estabas haciendo en la app?";
    static final String HANDOFF_DONE = "Listo, seguimos aquí. ¿Qué hacemos?";
    static final String HANDOFF_FAILED = "No pude traer esa conversación; seguimos desde aquí. ¿Qué hacemos?";
    static final String FRESH_DONE = "Perfecto, empezamos de cero. ¿Qué hacemos?";
    /** After this, WhatsApp has no place to keep the offer; the greeting is the usual one. */
    static final java.time.Duration APP_RECENT = java.time.Duration.ofHours(2);

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
            ProviderCredsResolver credsResolver,
            InboundImageLoader imageLoader,
            OutboundDispatcher outboundDispatcher,
            OptOutService optOuts,
            ConversationHoldService holds,
            ChannelTracing tracing,
            com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground
                    .PlaygroundSessionRegistry playgroundSessions,
            @Value("${yaya.agentic.channels.whatsapp.transcription.max-seconds:120}") int maxAudioSeconds,
            @Value("${yaya.agentic.channels.whatsapp.transcription.max-bytes:16777216}") long maxAudioBytes,
            StepUpCoordinator stepUp,
            com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthIntegrator authIntegrator,
            com.yayatechandinnovations.yayaagentic.channel.auth.SessionAuthTokenStore authTokenStore,
            com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthTrace authTrace,
            OfferedOptionsStore offeredOptions) {
        this.offeredOptions = offeredOptions;
        this.executor = executor;
        this.inbound = inbound;
        this.senders = senders;
        this.bindings = bindings;
        this.sessions = sessions;
        this.engine = engine;
        this.entityMemory = entityMemory;
        this.json = json;
        this.transcriber = transcriber;
        this.credsResolver = credsResolver;
        this.imageLoader = imageLoader;
        this.outboundDispatcher = outboundDispatcher;
        this.optOuts = optOuts;
        this.holds = holds;
        this.tracing = tracing;
        this.playgroundSessions = playgroundSessions;
        this.maxAudioSeconds = maxAudioSeconds;
        this.maxAudioBytes = maxAudioBytes;
        this.stepUp = stepUp;
        this.authIntegrator = authIntegrator;
        this.authTokenStore = authTokenStore;
        this.authTrace = authTrace;
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
                        OptOutService.stopConfirmation(sender.defaultLocale()),
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
                        OptOutService.startConfirmation(sender.defaultLocale()),
                        java.util.List.of());
            } catch (RuntimeException ignored) {}
            row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
            row.setBodyRedacted(body);
            inbound.save(row);
            return;
        }
        // 1a'. §15 — a code prompt may be waiting in this customer's
        // conversation. If this message is, or may hold, the code, the stored
        // row gives it up now: before any branch below can return, fail or
        // hand the text to something else.
        final Optional<Ids.SessionId> openSession = sessions.findOpen(
                new Ids.TenantId(row.getTenantId()), new SenderId(row.getSenderId()), row.getEndUserPhone());
        boolean secretInbound = redactIfCode(row, openSession, body);

        // §11.2 — if the user already opted out, ack without doing anything
        // (Meta policy forbids contacting them). They must START first.
        if (optOuts.isOptedOut(new SenderId(row.getSenderId()), row.getEndUserPhone())) {
            row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
            row.setProcessingError("recipient_opted_out");
            inbound.save(row);
            return;
        }

        // 1a''. Keyword actions (ticket-share design §8 P5): a word the tenant
        // answers deterministically — the inbound goes to the tenant's URL and
        // its reply comes back, with no engine run and no session. The row is
        // filed like any other inbound, so the 24 h window still opens.
        if (!secretInbound && answerKeywordAction(row, sender, body)) {
            row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
            row.setBodyRedacted(redact(body, row.getEndUserPhone()));
            inbound.save(row);
            return;
        }

        // 1c. §11.3 — a person took this conversation over: ack and keep the
        // text (the desk reads it back through the hold API), but never run
        // the engine, or the customer gets a bot reply on top of theirs.
        if (holds.isHeld(new Ids.TenantId(row.getTenantId()), row.getEndUserPhone())) {
            row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
            row.setProcessingError("human_hold");
            // The desk reads this text back; a code is not for the desk either.
            row.setBodyRedacted(secretInbound ? SecretInboundRedaction.MARKER
                    : redact(body, row.getEndUserPhone()));
            inbound.save(row);
            return;
        }

        // 1b. §10.3 — when the user tapped a button or selected a list row,
        // Twilio supplies ButtonText / ListTitle alongside Body. Normalize
        // the inbound to look like the user typed the label, and stamp the
        // structured ID into hints so a profile can short-circuit on it.
        InteractiveSelection sel = extractInteractiveSelection(raw);
        // A tapped option or a typed number maps back to the options this
        // person was offered (assistant-channels design §2.6): the engine gets
        // what the option sends, never the digit.
        Ids.TenantId tenantId = new Ids.TenantId(row.getTenantId());
        SenderId senderId = new SenderId(row.getSenderId());
        OfferedOptionsStore.Offered offeredSet = offeredOptions == null ? null
                : offeredOptions.recall(tenantId, senderId, row.getEndUserPhone()).orElse(null);
        OptionReply optionReply = optionReplyFor(sel, body, offeredSet);
        if (optionReply != null && optionReply.more()) {
            // «Más…»: the next page goes out and the engine is not woken (§10.3).
            outboundDispatcher.dispatchPage(tenantId, senderId, row.getEndUserPhone(),
                    offeredSet.atOffset(offeredSet.offset() + DefaultInteractiveRenderer.FIRST_PAGE_ROWS));
            row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
            row.setBodyRedacted(redact(body, row.getEndUserPhone()));
            inbound.save(row);
            return;
        }
        if (optionReply != null) {
            body = optionReply.send();
            if (offeredOptions != null) offeredOptions.forget(tenantId, senderId, row.getEndUserPhone());
        } else if (sel != null) {
            body = sel.label;
        }

        // 2. media gate (§3.3): what the assistant cannot take gets a fixed
        //    reply, never a silent drop and never a model call for it.
        List<MediaDescriptor> media = extractMedia(raw);
        InboundMediaGate.Plan plan = InboundMediaGate.classify(media, raw, transcriber.isPresent(),
                () -> usageMeter == null || usageMeter.hasVoiceAllowance(row.getTenantId()));
        List<String> fixedReplies = new ArrayList<>(plan.fixedReplies());
        // 2a. transcribe audio + mutate body if needed
        TranscriptionOutcome transcription = transcribeIfPresent(row, sender, plan.audio());
        if (transcription.text != null && !transcription.text.isBlank()) {
            body = body.isBlank()
                    ? transcription.text
                    : body + "\n\n[voice note: " + transcription.text + "]";
            // A code may be spoken: the transcript is checked before it is stored.
            secretInbound = secretInbound || redactIfCode(row, openSession, body);
            if (!secretInbound) {
                row.setMediaTranscriptsJson(transcription.transcriptsJson);
                inbound.save(row);
            }
        } else if (transcription.failureCode != null) {
            fixedReplies.add("audio_too_long".equals(transcription.failureCode)
                    ? InboundMediaGate.VOICE_TOO_LONG_REPLY : InboundMediaGate.VOICE_UNAVAILABLE_REPLY);
        }
        // 2b. inbound photos → the model sees them (vision) and tools can
        //     reference them by URL. See whatsapp-channel-design.md §9.5.
        List<InboundImageLoader.LoadedImage> images = loadImagesIfAccepted(sender, plan.images());
        Map<String, Object> attachments = new LinkedHashMap<>(imageAttachments(images));
        // 2b'. PDFs: downloaded now (sender's Twilio auth, ≤ 15 MB), kept as
        //      chat_attachments rows once the session is known.
        List<InboundDocument> documents = downloadDocuments(sender, plan.documents());
        // 2b''. A message with media and no text is still a turn: the text
        //       becomes «(foto)», «(documento: x.pdf)» or the transcript; codes
        //       read from the photos ride inside the untrusted-data markers.
        String mediaText = mediaText(body, images, documents);
        if (!fixedReplies.isEmpty()) {
            Ids.SessionId replyTo = openSession.orElseGet(() -> new Ids.SessionId(UUID.randomUUID().toString()));
            for (String reply : fixedReplies) {
                try {
                    outboundDispatcher.dispatchGreeting(new Ids.TenantId(row.getTenantId()),
                            new SenderId(row.getSenderId()), row.getEndUserPhone(), replyTo, reply, List.of());
                } catch (RuntimeException e) {
                    log.warn("fixed media reply not sent for {}: {}", messageSid, e.getMessage());
                }
            }
            if (body.isBlank() && mediaText.isBlank() && images.isEmpty() && documents.isEmpty()) {
                row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
                row.setProcessingError("media_unsupported");
                row.setBodyRedacted(body);
                inbound.save(row);
                return;
            }
        }
        // 2c. §6.5 — a tapped button whose payload names a bound action or a
        //     confirm frame reaches the engine as a structured selection, so the
        //     tap decides exactly that action/frame instead of being re-read as
        //     the typed label.
        Map<String, Object> selection = optionReply != null ? optionReply.selection() : selectionOf(sel);
        if (selection != null) attachments.put("_selection", selection);
        // The greeting's offer to continue the app conversation is answered
        // here, never by the model: a tap copies the context or declines it.
        final String handoffTap = sel == null ? null : handoffTapOf(sel.id(), sel.kind());
        String repliedTo = raw.get("OriginalRepliedMessageSid") instanceof String s && !s.isBlank() ? s : null;

        // 3. binding resolution — supplies the session policy + profile id
        var endpoint = new com.yayatechandinnovations.yayaagentic.channel.ChannelEndpointRef
                .WhatsAppSenderRef(new SenderId(row.getSenderId()));
        // The principal is minted by the authenticator chain when the engine
        // start runs; before that, the binding lookup just needs the routing
        // hints. We pass a null principal — only EndUserClaim rules need it.
        Map<String, Object> hints = buildHints(row, sender,
                secretInbound ? SecretInboundRedaction.MARKER : body, transcription);
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
        // §15: the engine reads a code out of the text only in the conversation
        // whose prompt it answers. If that conversation is not the one this
        // message continues (it idled out), the text goes on as the marker.
        final boolean codeForEarlierSession = secretInbound && fresh;
        String userText = consumedKeyword ? "" : codeForEarlierSession ? SecretInboundRedaction.MARKER
                : mediaText.isBlank() ? body : body.isBlank() ? mediaText : body + "\n\n" + mediaText;
        recordMediaUsage(row.getTenantId(), binding, images, transcription);

        // 5. build inputs for the engine. Leave explicitProfile empty: the bound
        //    profile comes from ChannelProfileResolver (@30) off the channel
        //    hints, uniformly with chat, so a resolver-selected override (@15)
        //    can win over it. explicitProfile now means only "a caller/operator
        //    named a profile." See resolver-profile-override-design.md §8.1.
        StartConversationRequest startReq = new StartConversationRequest(
                new Ids.TenantId(row.getTenantId()),
                Optional.empty(),
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
            // Under AUTO_REPLY the caller first hears the binding's fixed
            // line, once per 24 h; the drop and its audit row are the same.
            if (autoReplies != null) {
                autoReplies.replyIfDue(new Ids.TenantId(row.getTenantId()), new SenderId(row.getSenderId()),
                        row.getEndUserPhone(), binding);
            }
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

        final boolean secretRow = secretInbound;

        // 7. run the engine — start when fresh, send when reused
        if (fresh) {
            StartSessionResult result = engine.start(startReq, auth.onChannel(TurnChannel.WHATSAPP));
            sessionId = result.session().id();
            sessions.register(
                    new Ids.TenantId(row.getTenantId()),
                    new SenderId(row.getSenderId()),
                    row.getEndUserPhone(),
                    sessionId);
            registerPlaygroundClientSession(rawForAuth, sessionId);
            // §9 step 3e — persist the resolver-minted JWT on the freshly
            // created session row so the engine can forward it to tool calls
            // and refresh it on tool-level 401 (PR 6c).
            Ids.SessionId freshId = sessionId;
            authResolved.jwt().ifPresent(jwt ->
                    authTokenStore.persist(freshId, jwt));
            recordAuthFlowTrace(sessionId, row, binding, authResolved);
            captureEntities(sessionId, sender, row, transcription, policy);
            // W-A8 — playground pre-elevation: stamp the elevation row BEFORE
            // engine.send so the very first tool call sees it via the
            // StepUpScopeAuthorizer's elevation-merge path.
            applyPlaygroundPreElevationIfPresent(rawForAuth, row, sessionId);
            seedContext(row, sessionId, repliedTo);
            // A fresh session that opens WITHOUT a user message (a consumed
            // new-session keyword) gets the proactive greeting + quick-replies.
            // When the first inbound already carries content, ALSO sending the
            // static greeting double-introduces the bot — the engine reply
            // introduces itself on first contact via the personality "greet"
            // rule. Send only the reply in that case.
            if (handoffTap != null) {
                answerHandoffTap(row, sessionId, handoffTap, auth);
            } else if (userText.isBlank() && images.isEmpty() && documents.isEmpty()) {
                // C5: the user was in the app a moment ago — offer to continue it here.
                TurnEvent.UiHint offer = handoffOffer(row, result.session());
                outboundDispatcher.dispatchGreeting(
                        new Ids.TenantId(row.getTenantId()),
                        new SenderId(row.getSenderId()),
                        row.getEndUserPhone(),
                        sessionId,
                        offer == null ? result.greeting() : withOfferLine(result.greeting()),
                        result.quickReplies(),
                        offer);
            } else {
                // The same offer rides the reply when the first message already said something.
                TurnEvent.UiHint offer = handoffOffer(row, result.session());
                outboundDispatcher.dispatchTurn(
                        new Ids.TenantId(row.getTenantId()),
                        new SenderId(row.getSenderId()),
                        row.getEndUserPhone(),
                        sessionId,
                        com.yayatechandinnovations.yayaagentic.usage.QuotaRefusals.spoken(
                                sendTurn(row, sessionId, userText, attachments, documents, auth)),
                        offer);
            }
        } else {
            // §9 step 3e on continuation — refresh the session row's JWT
            // copy if the integrator produced a (possibly cache-hit) token.
            Ids.SessionId reusedId = sessionId;
            authResolved.jwt().ifPresent(jwt ->
                    authTokenStore.persist(reusedId, jwt));
            registerPlaygroundClientSession(rawForAuth, sessionId);
            recordAuthFlowTrace(sessionId, row, binding, authResolved);
            captureEntities(sessionId, sender, row, transcription, policy);
            applyPlaygroundPreElevationIfPresent(rawForAuth, row, sessionId);
            seedContext(row, sessionId, repliedTo);
            if (handoffTap != null) {
                answerHandoffTap(row, sessionId, handoffTap, auth);
            } else if (!userText.isBlank() || !images.isEmpty() || !documents.isEmpty()) {
                // §6.6 — when an OTP-shaped pending exists for this session,
                // interpret the inbound as the user's verification code BEFORE
                // routing to the engine. The LLM never sees the OTP digits.
                if (!userText.isBlank() && stepUp.hasOpenOtpPending(sessionId)
                        && handleStepUpVerify(row, sessionId, userText)) {
                    // Step-up dispatcher already sent the response; skip engine.
                } else {
                    try {
                        outboundDispatcher.dispatchTurn(
                                new Ids.TenantId(row.getTenantId()),
                                new SenderId(row.getSenderId()),
                                row.getEndUserPhone(),
                                sessionId,
                                com.yayatechandinnovations.yayaagentic.usage.QuotaRefusals.spoken(
                                        sendTurn(row, sessionId, userText, attachments, documents, auth)));
                    } catch (IllegalStateException stale) {
                        // The channel_sessions row survived a backend restart
                        // but the engine's session didn't. Self-heal: retire
                        // the stale mapping, open a fresh engine session, and
                        // replay this message into it — the user never sees
                        // the seam (beyond losing prior conversation memory).
                        if (stale.getMessage() == null
                                || !stale.getMessage().startsWith("unknown session")) {
                            throw stale;
                        }
                        log.warn("Engine session {} vanished (backend restart?) — "
                                + "reopening a fresh session for {}",
                                sessionId.value(), row.getEndUserPhone());
                        sessions.close(new Ids.TenantId(row.getTenantId()), sessionId,
                                "ENGINE_SESSION_LOST");
                        StartSessionResult healed = engine.start(startReq, auth.onChannel(TurnChannel.WHATSAPP));
                        Ids.SessionId healedId = healed.session().id();
                        sessions.register(
                                new Ids.TenantId(row.getTenantId()),
                                new SenderId(row.getSenderId()),
                                row.getEndUserPhone(),
                                healedId);
                        authResolved.jwt().ifPresent(jwt ->
                                authTokenStore.persist(healedId, jwt));
                        recordAuthFlowTrace(healedId, row, binding, authResolved);
                        captureEntities(healedId, sender, row, transcription, policy);
                        seedContext(row, healedId, repliedTo);
                        outboundDispatcher.dispatchTurn(
                                new Ids.TenantId(row.getTenantId()),
                                new SenderId(row.getSenderId()),
                                row.getEndUserPhone(),
                                healedId,
                                com.yayatechandinnovations.yayaagentic.usage.QuotaRefusals.spoken(sendTurn(row, healedId,
                                        // The prompt went with the lost session; the code must
                                        // not become an ordinary message of the new one.
                                        secretRow ? SecretInboundRedaction.MARKER : userText,
                                        attachments, documents, auth)));
                    }
                }
            }
        }

        row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
        row.setBodyRedacted(secretRow
                ? SecretInboundRedaction.MARKER
                : redact(body, row.getEndUserPhone()));
        inbound.save(row);
    }

    /**
     * P5: forward a keyword inbound and send the tenant's reply. True when a
     * keyword matched (whether or not a reply came back) — the caller files
     * the row and stops. Never throws: a tenant outage is a missing reply.
     */
    private boolean answerKeywordAction(InboundMessageEntity row, WhatsAppSender sender, String body) {
        if (keywordActions == null || keywordForwarder == null) return false;
        Ids.TenantId tenant = new Ids.TenantId(row.getTenantId());
        SenderId senderId = new SenderId(row.getSenderId());
        Optional<com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords.KeywordAction> action;
        try {
            action = keywordActions.match(tenant, senderId, body);
        } catch (RuntimeException e) {
            log.warn("keyword actions lookup failed for {}: {}", row.getMessageSid(), e.getMessage());
            return false;
        }
        if (action.isEmpty()) return false;
        // The inbound reopened the 24 h window (§10.2) — anything parked for this
        // caller fires now, ahead of the keyword reply, exactly as step 6 does
        // for inbounds that reach the engine.
        try {
            outboundDispatcher.releasePending(tenant, senderId, row.getEndUserPhone());
        } catch (RuntimeException e) {
            log.warn("parked outbound not released for {} on keyword inbound: {}", row.getMessageSid(), e.getMessage());
        }
        Optional<String> reply = keywordForwarder.forward(action.get(),
                new com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords.KeywordForwarder.Inbound(
                        tenant, senderId, row.getEndUserPhone(), sender.phoneE164(), body,
                        row.getReceivedAt() == null ? null : row.getReceivedAt().toInstant(),
                        row.getMessageSid()));
        reply.ifPresent(text -> {
            try {
                outboundDispatcher.dispatchGreeting(tenant, senderId, row.getEndUserPhone(),
                        new Ids.SessionId(UUID.randomUUID().toString()), text, List.of());
            } catch (RuntimeException e) {
                log.warn("keyword reply not sent for {}: {}", row.getMessageSid(), e.getMessage());
            }
        });
        row.setProcessingError(null);
        return true;
    }

    private TranscriptionOutcome transcribeIfPresent(InboundMessageEntity row, WhatsAppSender sender,
                                                     List<MediaDescriptor> media) {
        if (media.isEmpty()) return TranscriptionOutcome.empty();
        boolean enabled = transcriber.isPresent();
        if (!enabled) return TranscriptionOutcome.empty();

        // Twilio's media CDN enforces HTTP Basic auth on accounts that have
        // it enabled (and always for WhatsApp media on some account tiers) —
        // fetch with the same creds the sender uses for outbound, or the
        // download 401s and the voice note is silently dropped.
        String basicAuthHeader = null;
        try {
            if (credsResolver.isReady()
                    && credsResolver.resolve(sender) instanceof TwilioCreds tc) {
                basicAuthHeader = tc.basicAuthHeader();
            }
        } catch (RuntimeException e) {
            log.warn("Could not resolve Twilio creds for media download (sender {}): {}",
                    sender.id().value(), e.getMessage());
        }

        List<Map<String, Object>> transcripts = new ArrayList<>();
        StringBuilder combined = new StringBuilder();
        String detectedLanguage = null;
        String failureCode = null;
        long seconds = 0;
        for (MediaDescriptor m : media) {
            if (!transcriber.get().supportedContentTypes().contains(m.contentType())
                    && !transcriber.get().supportedContentTypes().contains(InboundMediaGate.bare(m.contentType()))) {
                failureCode = "unsupported_audio";
                continue;
            }
            try {
                MediaTranscriber.TranscriptionResult result = transcriber.get().transcribe(
                        new MediaTranscriber.InboundMediaRef(URI.create(m.mediaUrl()), m.contentType(), m.size()),
                        new MediaTranscriber.TranscriptionContext(
                                row.getTenantId(), row.getSenderId(), row.getMessageSid(),
                                Duration.ofSeconds(maxAudioSeconds), maxAudioBytes,
                                basicAuthHeader, sender.defaultLocale()));
                if (result.audioDuration() != null) seconds += result.audioDuration().toSeconds();
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
                failureCode = e.code();
            } catch (RuntimeException e) {
                log.warn("Transcriber failed for {}: {}", m.mediaUrl(), e.getMessage());
                failureCode = "transcriber_error";
            }
        }
        String json = writeJson(Map.of("items", transcripts));
        return new TranscriptionOutcome(combined.toString(), detectedLanguage, json, failureCode, seconds);
    }

    /* ------------------------- media (§3.3, §3.4) --------------------------- */

    /** A PDF downloaded from the BSP CDN, waiting for the session it belongs to. */
    record InboundDocument(String mediaUrl, String filename, String contentType, byte[] bytes) {}

    private List<InboundDocument> downloadDocuments(WhatsAppSender sender, List<MediaDescriptor> documents) {
        if (documents == null || documents.isEmpty() || mediaClient == null) return List.of();
        String basicAuthHeader = basicAuthFor(sender, "document");
        List<InboundDocument> out = new ArrayList<>();
        for (MediaDescriptor m : documents) {
            try {
                InboundMediaClient.Fetched f = mediaClient.fetchNamed(URI.create(m.mediaUrl()), basicAuthHeader,
                        ChatAttachmentService.DOCUMENT_MAX_BYTES);
                String name = f.filename() == null || f.filename().isBlank() ? "documento.pdf" : f.filename();
                out.add(new InboundDocument(m.mediaUrl(), name, InboundMediaGate.bare(m.contentType()), f.bytes()));
            } catch (RuntimeException e) {
                log.warn("Could not fetch inbound document {}: {}", m.mediaUrl(), e.getMessage());
            }
        }
        return out;
    }

    private String basicAuthFor(WhatsAppSender sender, String what) {
        try {
            if (credsResolver.isReady() && credsResolver.resolve(sender) instanceof TwilioCreds tc) {
                return tc.basicAuthHeader();
            }
        } catch (RuntimeException e) {
            log.warn("Could not resolve Twilio creds for {} download (sender {}): {}",
                    what, sender.id().value(), e.getMessage());
        }
        return null;
    }

    /**
     * What a media-only message says, plus the codes read from the photos:
     * «(foto)» / «(documento: x.pdf)» when there is no text, and one
     * «Código leído en la imagen» block per photo that had one.
     */
    static String mediaText(String body, List<InboundImageLoader.LoadedImage> images,
                            List<InboundDocument> documents) {
        StringBuilder sb = new StringBuilder();
        if (body == null || body.isBlank()) {
            for (int i = 0; i < (images == null ? 0 : images.size()); i++) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(InboundMediaGate.PHOTO_PLACEHOLDER);
            }
            for (InboundDocument d : documents == null ? List.<InboundDocument>of() : documents) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append("(documento: ").append(d.filename()).append(')');
            }
        }
        for (InboundImageLoader.LoadedImage img : images == null ? List.<InboundImageLoader.LoadedImage>of() : images) {
            String block = ChatMessageComposer.codesBlock(img.codes());
            if (block != null) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append(block);
            }
        }
        return sb.toString();
    }

    /**
     * Runs the engine turn: PDFs become {@code chat_attachments} rows of this
     * session and ride the composed message (text inside the untrusted-data
     * markers, metadata on the user turn), then are marked referenced with the
     * turn's id when the turn ends.
     */
    private Flux<TurnEvent> sendTurn(InboundMessageEntity row, Ids.SessionId sessionId, String userText,
                                     Map<String, Object> attachments, List<InboundDocument> documents,
                                     AuthContext auth) {
        UserMessage message = new UserMessage(userText, attachments);
        List<String> attachmentIds = List.of();
        if (documents != null && !documents.isEmpty() && chatAttachments != null) {
            Ids.TenantId tenant = new Ids.TenantId(row.getTenantId());
            List<ChatAttachmentService.ChatAttachment> stored = new ArrayList<>();
            for (InboundDocument d : documents) {
                try {
                    stored.add(chatAttachments.upload(tenant, sessionId.value(),
                            ChatAttachmentService.Uploader.subject("whatsapp:" + row.getEndUserPhone(), null, null, null),
                            d.filename(), d.bytes()).attachment());
                } catch (ChatAttachmentService.RejectedException e) {
                    log.info("inbound document {} not kept: {}", d.filename(), e.code());
                }
            }
            if (!stored.isEmpty()) {
                UserMessage composed = ChatMessageComposer.compose(userText, stored, id -> null);
                Map<String, Object> merged = new LinkedHashMap<>(attachments);
                merged.putAll(composed.attachments());
                message = new UserMessage(composed.text(), merged);
                attachmentIds = stored.stream().map(ChatAttachmentService.ChatAttachment::id).toList();
            }
        }
        final List<String> ids = attachmentIds;
        Flux<TurnEvent> events = engine.send(sessionId, message, auth.onChannel(TurnChannel.WHATSAPP));
        if (ids.isEmpty()) return events;
        return events.doOnNext(evt -> {
            if (evt instanceof TurnEvent.End end) {
                try {
                    chatAttachments.markReferenced(ids, end.turnId().value());
                } catch (RuntimeException e) {
                    log.warn("could not mark inbound documents referenced: {}", e.getMessage());
                }
            }
        });
    }

    private void recordMediaUsage(String tenantId, ChannelBinding binding,
                                  List<InboundImageLoader.LoadedImage> images, TranscriptionOutcome transcription) {
        if (usageMeter == null) return;
        String profileId = binding == null || binding.profile() == null ? null : binding.profile().value();
        try {
            if (images != null && !images.isEmpty()) usageMeter.recordImagesIn(tenantId, profileId, images.size());
            if (transcription != null && transcription.seconds > 0
                    && transcription.text != null && !transcription.text.isBlank()) {
                usageMeter.recordVoiceSeconds(tenantId, profileId, transcription.seconds);
            }
        } catch (RuntimeException e) {
            log.debug("media usage not recorded: {}", e.toString());
        }
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
        // Session Start Flow binding (docs/design/session-start-flows.md §6):
        // channel binding wins over the profile default in the resolver.
        if (sender.startFlowId() != null && !sender.startFlowId().isBlank()) {
            hints.put("channel.startFlowId", sender.startFlowId());
        }

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

    record InteractiveSelection(String id, String label, String kind) {}

    /** A tapped option or a typed number resolved against what was offered; {@code more} asks for the next page. */
    record OptionReply(String send, Map<String, Object> selection, boolean more) {}

    /**
     * §2.6 — {@code ListId} or a button payload {@code opt:<id>} → the offered
     * option's {@code send}; a reply that is only a number after a numbered-text
     * list → that row's {@code send}; the «Más…» row → the next page. Null when
     * the message is none of these (a frame or action button, plain text).
     */
    static OptionReply optionReplyFor(InteractiveSelection sel, String body, OfferedOptionsStore.Offered offered) {
        if (sel != null) {
            String id = sel.id() == null ? "" : sel.id().trim();
            if (id.startsWith(DefaultInteractiveRenderer.CONFIRM_PREFIX)
                    || id.startsWith(DefaultInteractiveRenderer.CANCEL_PREFIX)
                    || id.startsWith(DefaultInteractiveRenderer.ACTION_PREFIX)) {
                return null;
            }
            boolean names = id.startsWith(DefaultInteractiveRenderer.OPTION_PREFIX) || "list_row".equals(sel.kind());
            if (!names || id.isEmpty()) return null;
            Optional<com.yayatechandinnovations.yayaagentic.engine.QuickReplies.Option> found =
                    OfferedOptionsStore.byId(offered, id);
            if (found.isPresent()) return reply(found.get());
            // The offered set is gone (expired, restarted): the tap still names
            // its option, and its label is what it sends — as before.
            String bare = id.startsWith(DefaultInteractiveRenderer.OPTION_PREFIX)
                    ? id.substring(DefaultInteractiveRenderer.OPTION_PREFIX.length()) : id;
            if (bare.isBlank() || DefaultInteractiveRenderer.MORE_ROW_ID.equals(bare)) return null;
            String send = sel.label() == null || sel.label().isBlank() ? bare : sel.label();
            return new OptionReply(send, optionSelection(bare, send), false);
        }
        return OfferedOptionsStore.byNumber(offered, body).map(InboundDispatchWorker::reply).orElse(null);
    }

    private static OptionReply reply(com.yayatechandinnovations.yayaagentic.engine.QuickReplies.Option option) {
        if (DefaultInteractiveRenderer.MORE_ROW_ID.equals(option.id())) {
            return new OptionReply(option.send(), null, true);
        }
        return new OptionReply(option.send(), optionSelection(option.id(), option.send()), false);
    }

    static Map<String, Object> optionSelection(String id, String send) {
        return Map.of("kind", "option", "id", id, "send", send);
    }

    /** Button payload → engine selection (§6.5); null for plain quick replies. */
    private static Map<String, Object> selectionOf(InteractiveSelection sel) {
        return sel == null ? null : selectionFor(sel.id(), sel.kind());
    }

    static Map<String, Object> selectionFor(String payload, String kind) {
        if (payload == null || !"button".equals(kind)) return null;
        String id = payload.trim();
        if (id.startsWith(DefaultInteractiveRenderer.ACTION_PREFIX)) {
            String actionId = id.substring(DefaultInteractiveRenderer.ACTION_PREFIX.length());
            return actionId.isBlank() || "none".equals(actionId) ? null
                    : Map.of("kind", "action", "actionId", actionId);
        }
        if (id.startsWith(DefaultInteractiveRenderer.CONFIRM_PREFIX)) {
            return Map.of("kind", "confirm", "callId", id.substring(DefaultInteractiveRenderer.CONFIRM_PREFIX.length()));
        }
        if (id.startsWith(DefaultInteractiveRenderer.CANCEL_PREFIX)) {
            return Map.of("kind", "cancel", "callId", id.substring(DefaultInteractiveRenderer.CANCEL_PREFIX.length()));
        }
        return null;
    }

    /**
     * {@code handoff:<sessionId>} or {@code fresh} from a tapped button; null for
     * anything else. The renderer sends every option as {@code opt:<id>}, so the
     * prefix is stripped before the id is read.
     */
    static String handoffTapOf(String payload, String kind) {
        if (payload == null || !"button".equals(kind)) return null;
        String id = payload.trim();
        if (id.startsWith(DefaultInteractiveRenderer.OPTION_PREFIX)) {
            id = id.substring(DefaultInteractiveRenderer.OPTION_PREFIX.length()).trim();
        }
        if (id.equals(FRESH_ID)) return FRESH_ID;
        if (id.startsWith(HANDOFF_PREFIX) && id.length() > HANDOFF_PREFIX.length()) return id;
        return null;
    }

    static TurnEvent.UiHint handoffOfferHint(Ids.SessionId appSession) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("options", List.of(
                option(HANDOFF_PREFIX + appSession.value(), HANDOFF_LABEL),
                option(FRESH_ID, FRESH_LABEL)));
        payload.put("source", "start");
        return new TurnEvent.UiHint("quick_replies", payload);
    }

    private static Map<String, Object> option(String id, String label) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", id);
        o.put("label", label);
        o.put("send", label);
        return o;
    }

    static String withOfferLine(String greeting) {
        String base = greeting == null ? "" : greeting.strip();
        return base.isEmpty() ? HANDOFF_OFFER_LINE : base + "\n\n" + HANDOFF_OFFER_LINE;
    }

    /** The two buttons when the same person had an app conversation in the last two hours (C5). */
    private TurnEvent.UiHint handoffOffer(InboundMessageEntity row,
                                          com.yayatechandinnovations.yayaagentic.core.Session session) {
        if (handoffs == null || session == null || session.principal() == null) return null;
        try {
            return handoffs.recentOn(row.getTenantId(), session.principal().subject(),
                            session.profile() == null ? null : session.profile().value(), "admin-app", APP_RECENT)
                    .map(InboundDispatchWorker::handoffOfferHint)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("hand-off offer skipped for session {}: {}", session.id().value(), e.toString());
            return null;
        }
    }

    /** Runs the hand-off the user tapped (or declines it) and answers with a fixed line — no model call. */
    private void answerHandoffTap(InboundMessageEntity row, Ids.SessionId sessionId, String tap,
                                  AuthContext auth) {
        String reply;
        if (FRESH_ID.equals(tap)) {
            reply = FRESH_DONE;
        } else if (handoffs == null) {
            reply = HANDOFF_FAILED;
        } else {
            reply = HANDOFF_FAILED;
            try {
                handoffs.perform(sessionId, new Ids.SessionId(tap.substring(HANDOFF_PREFIX.length())),
                        auth.onChannel(TurnChannel.WHATSAPP));
                reply = HANDOFF_DONE;
            } catch (com.yayatechandinnovations.yayaagentic.engine.handoff.HandoffException refused) {
                log.info("hand-off into {} refused: {}", sessionId.value(), refused.code());
            } catch (RuntimeException e) {
                log.warn("hand-off into {} failed: {}", sessionId.value(), e.toString());
            }
        }
        outboundDispatcher.dispatchText(new Ids.TenantId(row.getTenantId()), new SenderId(row.getSenderId()),
                row.getEndUserPhone(), sessionId, reply);
    }

    private void seedContext(InboundMessageEntity row, Ids.SessionId sessionId, String repliedTo) {
        if (contextSeeder == null) return;
        contextSeeder.seed(new Ids.TenantId(row.getTenantId()), new SenderId(row.getSenderId()),
                row.getEndUserPhone(), sessionId, repliedTo);
    }

    /**
     * Map the engine's internal session id to the client-chosen id the
     * playground UI polls with (stashed by the controller in
     * {@code raw_payload_json._playgroundClientSessionId}). No-op for the real
     * Twilio path, where the marker is absent. See {@link
     * com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundSessionRegistry}.
     */
    private void registerPlaygroundClientSession(Map<String, Object> raw,
                                                 Ids.SessionId sessionId) {
        if (raw == null) return;
        Object clientId = raw.get(
                com.yayatechandinnovations.yayaagentic.api.PlaygroundWhatsAppController
                        .RAW_PLAYGROUND_CLIENT_SESSION_KEY);
        if (clientId instanceof String s && !s.isBlank()) {
            playgroundSessions.register(sessionId, s);
        }
    }

    /**
     * Record the binding / JWT-verification / session-token stages for the
     * playground auth-flow inspector. No-op-safe: the trace is in-memory and
     * bounded. The resolver-call detail (status, http_status, latency) is
     * already in {@code auth_resolver_calls}; this captures only what the
     * worker computes that isn't otherwise persisted. See
     * {@code playground-auth-flow-inspector-design.md} §6.2.
     */
    private void recordAuthFlowTrace(
            Ids.SessionId sessionId,
            InboundMessageEntity row,
            ChannelBinding binding,
            com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthIntegrator.Resolved authResolved) {
        // Caller hash matches how AuthResolverDispatcher keys auth_resolver_calls,
        // so the inspector can recover the FRESH-session resolver call (audited
        // with a null session_id because resolve() runs before the engine mints
        // the session id). The playground uses a unique phone per session, so
        // this hash is effectively session-unique.
        String callerHash = com.yayatechandinnovations.yayaagentic.channel.auth.cache
                .AuthResolverTokenCache.sha256LowerHex(row.getEndUserPhone());
        authTrace.recordBinding(sessionId,
                String.valueOf(binding.id().value()),
                binding.profile().value(),
                binding.authResolverId(),
                binding.onUnresolved() == null ? null : binding.onUnresolved().name(),
                row.getTenantId(),
                callerHash);

        String decision = authResolved.decision().name();
        String detail = switch (authResolved.decision()) {
            case JWT_VERIFIED -> "Resolver JWT verified by the trust chain.";
            case ANONYMOUS -> binding.authResolverId() == null
                    ? "No auth resolver on this binding (FAQ mode) — anonymous principal."
                    : authResolved.reason() + "; on_unresolved=ANONYMOUS.";
            case REJECT -> authResolved.reason() + "; on_unresolved="
                    + (binding.onUnresolved() == null ? "REJECT" : binding.onUnresolved().name()) + ".";
        };
        authTrace.recordVerification(sessionId, decision, detail);

        // Bound vs effective profile (resolver-profile-override-design.md §9).
        String boundProfile = binding.profile().value();
        String override = authResolved.appliedProfileOverride().orElse(null);
        authTrace.recordProfileResolution(sessionId,
                override == null ? boundProfile : override,
                override == null ? "BOUND" : "RESOLVER_OVERRIDE",
                authResolved.overrideDeniedReason().orElse(null));

        com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthTrace.TokenState tokenState;
        if (authResolved.jwt().isEmpty()) {
            tokenState = com.yayatechandinnovations.yayaagentic.channel.auth
                    .ChannelAuthTrace.TokenState.ABSENT;
        } else if (!authTokenStore.isPersistable()) {
            tokenState = com.yayatechandinnovations.yayaagentic.channel.auth
                    .ChannelAuthTrace.TokenState.CIPHER_DISABLED;
        } else {
            tokenState = com.yayatechandinnovations.yayaagentic.channel.auth
                    .ChannelAuthTrace.TokenState.STORED;
        }
        authTrace.recordSessionToken(sessionId, tokenState);
    }

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

    /**
     * Images are accepted unless the sender's metadata says
     * {@code acceptInboundImages: false}. Twilio's CDN wants the sender's
     * Basic auth, resolved the same way the transcriber does.
     */
    private List<InboundImageLoader.LoadedImage> loadImagesIfAccepted(WhatsAppSender sender,
                                                                       List<MediaDescriptor> media) {
        if (media == null || media.isEmpty()) return List.of();
        if (media.stream().noneMatch(m -> InboundImageLoader.isImage(m.contentType()))) return List.of();
        Object flag = sender.metadata() == null ? null : sender.metadata().get("acceptInboundImages");
        if (flag != null && "false".equalsIgnoreCase(flag.toString())) {
            log.info("Sender {} rejects inbound images; {} dropped", sender.id().value(), media.size());
            return List.of();
        }
        return imageLoader.load(media, basicAuthFor(sender, "image"));
    }

    /**
     * {@code chat.images} = inline parts the engine turns into vision input
     * ({@code DefaultConversationEngine.currentUserEntry}); {@code images} =
     * URL metadata an HTTP tool argument can reference ({@code mediaUrl}).
     */
    static Map<String, Object> imageAttachments(List<InboundImageLoader.LoadedImage> images) {
        if (images == null || images.isEmpty()) return Map.of();
        Map<String, Object> attachments = new LinkedHashMap<>();
        attachments.put("chat.images", images.stream().map(InboundImageLoader.LoadedImage::inlinePart).toList());
        attachments.put("images", images.stream().map(InboundImageLoader.LoadedImage::metadata).toList());
        return attachments;
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

    /**
     * §15 — takes the text out of {@code row} (and saves it) when a code
     * prompt is waiting in {@code session} and {@code text} is, or may hold,
     * the code. The save is its own write: it does not wait for the rest of
     * the processing to succeed.
     */
    private boolean redactIfCode(InboundMessageEntity row, Optional<Ids.SessionId> session, String text) {
        if (!SecretInboundRedaction.holdsCode(engine, session, text, TurnChannel.WHATSAPP)) return false;
        SecretInboundRedaction.apply(row, json);
        inbound.save(row);
        return true;
    }

    /** As above for a row whose processing stopped at an unknown point; never throws. */
    private void redactFailedRowIfCode(InboundMessageEntity row) {
        try {
            Object body = readRaw(row.getRawPayloadJson()).get("Body");
            if (body == null) return;
            Optional<Ids.SessionId> session = sessions.findOpen(new Ids.TenantId(row.getTenantId()),
                    new SenderId(row.getSenderId()), row.getEndUserPhone());
            if (SecretInboundRedaction.holdsCode(engine, session, body.toString(), TurnChannel.WHATSAPP)) {
                SecretInboundRedaction.apply(row, json);
            }
        } catch (RuntimeException e) {
            log.warn("InboundDispatchWorker: could not check failed row {} for a code: {}",
                    row.getMessageSid(), e.getClass().getSimpleName());
        }
    }

    @Transactional
    protected void markFailed(String messageSid, String error) {
        inbound.findById(messageSid).ifPresent(row -> {
            // A row that failed before, or outside, the check in the worker
            // (a full pool, an exception early on) still gives up a code.
            redactFailedRowIfCode(row);
            row.setProcessingError(error);
            row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
            inbound.save(row);
        });
    }

    record MediaDescriptor(String mediaUrl, String contentType, long size) implements InboundImageLoader.MediaRef {
        @Override
        public long sizeBytes() {
            return size;
        }
    }

    /** {@code failureCode} is set when a voice note could not be transcribed; {@code seconds} = audio transcribed. */
    record TranscriptionOutcome(String text, String detectedLanguage, String transcriptsJson,
                                String failureCode, long seconds) {
        static TranscriptionOutcome empty() {
            return new TranscriptionOutcome(null, null, null, null, 0);
        }
    }
}
