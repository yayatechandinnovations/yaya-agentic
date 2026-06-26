package com.yayatechandinnovations.yayaagentic.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yayatechandinnovations.yayaagentic.auth.*;
import com.yayatechandinnovations.yayaagentic.core.*;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityKind;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityMemory;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityRef;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.HelloWorldProfileBootstrap;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.engine.confirm.EntityConflictDetector;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalContext;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalQuery;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievalResult;
import com.yayatechandinnovations.yayaagentic.knowledge.RetrievedChunk;
import com.yayatechandinnovations.yayaagentic.knowledge.Retriever;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import com.yayatechandinnovations.yayaagentic.personality.PersonalityFragment;
import com.yayatechandinnovations.yayaagentic.personality.PersonalityProvider;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import com.yayatechandinnovations.yayaagentic.profile.ProfileResolverChain;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.recorder.ConversationRecorder;
import com.yayatechandinnovations.yayaagentic.recorder.LoadOptions;
import com.yayatechandinnovations.yayaagentic.recorder.Operations;
import com.yayatechandinnovations.yayaagentic.recorder.RecordedSession;
import com.yayatechandinnovations.yayaagentic.recorder.RecorderContext;
import com.yayatechandinnovations.yayaagentic.recorder.RecorderRouter;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.tool.EntityCapture;
import com.yayatechandinnovations.yayaagentic.tool.OnConflict;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolExecutor;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.EntityBinder;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.JsonPointerExtractor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M0 engine. Wires Personality + Profile + Recorder + LlmClient + ToolExecutor.
 * <p>
 * M0 deliberately does NOT integrate the LLM with tool-calling. Instead, the
 * engine recognizes a leading {@code "/echo "} / {@code "echo "} in the user
 * message and dispatches the echo tool directly — enough to prove the bean
 * dispatch path end-to-end. Real LLM tool-calling lands in M2.
 */
@Component
public class DefaultConversationEngine implements ConversationEngine {

    private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

    private final PersonalityProvider personalityProvider;
    private final ProfileResolverChain resolverChain;
    private final ProfileRegistry profileRegistry;
    private final Authenticator authenticator;
    private final Authorizer authorizer;
    private final RecorderRouter recorderRouter;
    private final LlmClient llmClient;
    private final ToolExecutor toolExecutor;
    private final M0Catalog catalog;
    private final com.yayatechandinnovations.yayaagentic.engine.IntentTracker intentTracker;
    private final com.yayatechandinnovations.yayaagentic.memory.WorkingMemory workingMemory;
    private final PromptBuilder promptBuilder;
    private final com.yayatechandinnovations.yayaagentic.engine.confirm.ConfirmDetector confirmDetector;
    private final Retriever retriever;
    private final YayaAgenticProperties.Memory memoryConfig;
    private final ObjectMapper fingerprintMapper;
    private final EntityMemory entityMemory;
    private final EntityConflictDetector entityConflictDetector;
    private final JsonPointerExtractor entityExtractor;
    private final com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpCoordinator stepUpCoordinator;

    private final Map<Ids.SessionId, Session> active = new ConcurrentHashMap<>();
    private final Map<Ids.SessionId, IntentFrame> activeIntents = new ConcurrentHashMap<>();
    private final Map<Ids.SessionId, java.util.concurrent.atomic.AtomicInteger> nextTurnIdx = new ConcurrentHashMap<>();
    private final Map<Ids.SessionId, PromptBuilder.PromptPayload> lastPromptBySession = new ConcurrentHashMap<>();
    private final Map<Ids.SessionId, RetrievalResult> lastRetrievalBySession = new ConcurrentHashMap<>();

    public DefaultConversationEngine(PersonalityProvider personalityProvider,
                                     ProfileResolverChain resolverChain,
                                     ProfileRegistry profileRegistry,
                                     Authenticator authenticator,
                                     Authorizer authorizer,
                                     RecorderRouter recorderRouter,
                                     LlmClient llmClient,
                                     ToolExecutor toolExecutor,
                                     M0Catalog catalog,
                                     com.yayatechandinnovations.yayaagentic.engine.IntentTracker intentTracker,
                                     com.yayatechandinnovations.yayaagentic.memory.WorkingMemory workingMemory,
                                     PromptBuilder promptBuilder,
                                     com.yayatechandinnovations.yayaagentic.engine.confirm.ConfirmDetector confirmDetector,
                                     Retriever retriever,
                                     YayaAgenticProperties props,
                                     EntityMemory entityMemory,
                                     EntityConflictDetector entityConflictDetector,
                                     ObjectMapper sharedObjectMapper,
                                     com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpCoordinator stepUpCoordinator) {
        this.personalityProvider = personalityProvider;
        this.resolverChain = resolverChain;
        this.profileRegistry = profileRegistry;
        this.authenticator = authenticator;
        this.authorizer = authorizer;
        this.recorderRouter = recorderRouter;
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.catalog = catalog;
        this.intentTracker = intentTracker;
        this.workingMemory = workingMemory;
        this.promptBuilder = promptBuilder;
        this.confirmDetector = confirmDetector;
        this.retriever = retriever;
        this.memoryConfig = props != null && props.session() != null && props.session().memory() != null
                ? props.session().memory()
                : YayaAgenticProperties.Memory.defaults();
        // Order-stable JSON for tool-args fingerprinting. Two args maps that
        // differ only in key order must produce the same fingerprint, so a
        // retry of the same operation matches the user's prior consent.
        this.fingerprintMapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.entityMemory = entityMemory;
        this.entityConflictDetector = entityConflictDetector;
        this.entityExtractor = new JsonPointerExtractor(sharedObjectMapper);
        this.stepUpCoordinator = stepUpCoordinator;
    }

    @Override
    public StartSessionResult start(StartConversationRequest req, AuthContext auth) {
        Principal principal = authenticator.authenticate(auth);

        Ids.ProfileId profileId = resolverChain.resolve(req, auth, principal)
                .orElse(HelloWorldProfileBootstrap.HELLO_WORLD_PROFILE);

        Profile profile = profileRegistry.find(req.tenant(), profileId)
                .orElseThrow(() -> new IllegalStateException("profile not registered: " + profileId.value() + "@" + profileId.version()));

        Session session = new Session(
                new Ids.SessionId(UUID.randomUUID().toString()),
                req.tenant(),
                principal,
                profileId,
                req.channel(),
                List.of(),
                IntentFrame.empty(),
                Map.of(),
                Instant.now(),
                null
        );

        ConversationRecorder recorder = recorderRouter.recorderFor(session,
                new RecorderRouter.RecordingDecisionContext(Map.of()));
        recorder.onSessionStarted(session, ctx(req.tenant(), principal));
        active.put(session.id(), session);

        @SuppressWarnings("unchecked")
        List<String> quickReplies = (List<String>) profile.metadata().getOrDefault("introQuickReplies", List.of());
        return new StartSessionResult(session, profileId, profile.introOneLiner(), quickReplies);
    }

    @Override
    public Flux<TurnEvent> send(Ids.SessionId sessionId, UserMessage message, AuthContext auth) {
        Session session = active.get(sessionId);
        if (session == null) {
            return Flux.error(new IllegalStateException("unknown session: " + sessionId.value()));
        }

        Principal principal = session.principal();
        Profile profile = profileRegistry.find(session.tenant(), session.profile()).orElseThrow();
        PersonalityFragment personality = personalityProvider.forTenant(session.tenant(), DEFAULT_LOCALE);
        ConversationRecorder recorder = recorderRouter.recorderFor(session,
                new RecorderRouter.RecordingDecisionContext(Map.of()));

        Ids.TurnId turnId = new Ids.TurnId(UUID.randomUUID().toString());
        Map<String, Object> execAttrs = new java.util.HashMap<>();
        if (auth != null && auth.headers() != null) {
            String authzHeader = auth.headers().get("Authorization");
            if (authzHeader == null) authzHeader = auth.headers().get("authorization");
            if (authzHeader != null) execAttrs.put("inboundAuthorization", authzHeader);

            String overrideHost = auth.headers().get("X-Yaya-Host-Base-Url");
            if (overrideHost == null) overrideHost = auth.headers().get("x-yaya-host-base-url");
            if (overrideHost != null && !overrideHost.isBlank()) {
                String trimmed = overrideHost.trim();
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    execAttrs.put("hostBaseUrlOverride", trimmed);
                }
                // Silently drop malformed overrides — the dispatcher would
                // reject anyway, and we don't want a typoed inbound header
                // to fail a turn that has a perfectly good tenant default.
            }
        }
        ExecutionContext exec = new ExecutionContext(
                principal, sessionId, turnId, UUID.randomUUID().toString(), Map.copyOf(execAttrs));

        Map<String, Object> wm = workingMemory.get(sessionId);

        // ---- M2-D: confirm/cancel for a pending confirmable dispatch ----
        @SuppressWarnings("unchecked")
        Map<String, Object> pendingConfirm = pendingMapOf(wm, "pending_confirm");
        if (pendingConfirm != null) {
            var signal = confirmDetector.detect(message.text());
            switch (signal) {
                case CONFIRM -> {
                    String toolId = String.valueOf(pendingConfirm.get("toolId"));
                    Map<String, Object> args = (Map<String, Object>) pendingConfirm
                            .getOrDefault("args", Map.of());
                    String callIdHint = pendingConfirm.get("callId") == null
                            ? UUID.randomUUID().toString()
                            : String.valueOf(pendingConfirm.get("callId"));
                    workingMemory.remove(sessionId, "pending_confirm");
                    // Record that the user consented for this exact operation
                    // (tool + args) in this turn, so a retry the LLM proposes
                    // later in the same turn skips the confirm gate. Keyed on
                    // args fingerprint so a tool with different args still
                    // earns its own confirmation.
                    workingMemory.merge(sessionId, Map.of("consumed_confirm", Map.of(
                            "toolId", toolId,
                            "argsFingerprint", argsFingerprint(args),
                            "turnId", turnId.value())));
                    IntentFrame intentForResume = activeIntents.getOrDefault(sessionId,
                            session.activeIntent() == null ? IntentFrame.empty() : session.activeIntent());
                    Flux<TurnEvent> resumed = resumeConfirmedDispatch(
                            new Ids.ToolId(toolId), args, callIdHint,
                            profile, personality, session, intentForResume,
                            exec, recorder, turnId, message);
                    if (resumed != null) return resumed.concatWith(Mono.fromCallable(
                            () -> TurnEvent.End.of(turnId, 0, 0)));
                }
                case CANCEL -> {
                    workingMemory.remove(sessionId, "pending_confirm");
                    String cancelText = "OK — cancelled.";
                    Flux<TurnEvent> cancelled = Flux.<TurnEvent>just(new TurnEvent.Token(cancelText))
                            .doOnComplete(() -> recordTurns(recorder, session, exec, turnId, message,
                                    cancelText, List.of(), List.of()));
                    return cancelled.concatWith(Mono.fromCallable(
                            () -> TurnEvent.End.of(turnId, 0, 0)));
                }
                case UNCLEAR -> {
                    // pivot away from the pending confirm — drop it, let
                    // the rest of the turn handle the new intent.
                    workingMemory.remove(sessionId, "pending_confirm");
                }
            }
        }

        // ---- M2.10: resolve a pending entity-conflict (ASK_USER mode) ----
        // Mirrors the pending_confirm state machine: the prior turn surfaced
        // a conflict via UiHint + working memory, the LLM paraphrased the
        // question, and the user's reply now routes to continue / replace /
        // view / pivot. See docs/design/entity-memory-design.md §7.
        @SuppressWarnings("unchecked")
        Map<String, Object> pendingEntityConflict = pendingMapOf(wm, "pending_entity_conflict");
        if (pendingEntityConflict != null) {
            var signal = entityConflictDetector.detect(message.text());
            String entityName = String.valueOf(pendingEntityConflict.get("entityName"));
            switch (signal) {
                case CONTINUE_EXISTING -> {
                    // Drop the incoming; existing memory is unchanged.
                    workingMemory.remove(sessionId, "pending_entity_conflict");
                    // Let the LLM continue the turn normally — it will see
                    // the resolved memory state and can proceed.
                }
                case REPLACE -> {
                    workingMemory.remove(sessionId, "pending_entity_conflict");
                    String rollbackTool = (String) pendingEntityConflict.get("rollbackTool");
                    EntityRef incoming = (EntityRef) pendingEntityConflict.get("incoming");
                    IntentFrame intentForResume = activeIntents.getOrDefault(sessionId,
                            session.activeIntent() == null ? IntentFrame.empty() : session.activeIntent());
                    if (rollbackTool != null && !rollbackTool.isBlank()
                            && catalog.tool(new Ids.ToolId(rollbackTool)).isPresent()) {
                        // Dispatch rollback against the still-current entity
                        // (bindings on the rollback tool will fill its args
                        // from memory). On success, the rollback continuation
                        // captures the incoming entity below.
                        Flux<TurnEvent> rollback = resumeReplaceWithRollback(
                                new Ids.ToolId(rollbackTool), incoming,
                                profile, personality, session, intentForResume,
                                exec, recorder, turnId, message);
                        if (rollback != null) {
                            return rollback.concatWith(Mono.fromCallable(
                                    () -> TurnEvent.End.of(turnId, 0, 0)));
                        }
                    }
                    // No rollback tool declared — overwrite memory and let
                    // the LLM continue the turn with the new entity.
                    if (incoming != null) {
                        entityMemory.capture(sessionId, incoming);
                    }
                }
                case VIEW_EXISTING -> {
                    String describeTool = (String) pendingEntityConflict.get("describeTool");
                    if (describeTool != null && !describeTool.isBlank()
                            && catalog.tool(new Ids.ToolId(describeTool)).isPresent()) {
                        IntentFrame intentForResume = activeIntents.getOrDefault(sessionId,
                                session.activeIntent() == null ? IntentFrame.empty() : session.activeIntent());
                        // Don't clear pending_entity_conflict — after the
                        // user sees the existing entity, we want to re-ask.
                        Flux<TurnEvent> view = dispatchByToolId(
                                new Ids.ToolId(describeTool), Map.of(),
                                profile, personality, session, exec, recorder, turnId, message,
                                /* skipConfirm */ true, /* callIdHint */ null,
                                /* llmDriven */ true);
                        if (view != null) {
                            return view.concatWith(Mono.fromCallable(
                                    () -> TurnEvent.End.of(turnId, 0, 0)));
                        }
                    }
                    // No describe tool — drop pending and let the LLM
                    // describe from whatever working-memory state it sees.
                    workingMemory.remove(sessionId, "pending_entity_conflict");
                }
                case UNCLEAR -> {
                    // Pivot — drop the pending conflict, let the rest of
                    // the turn handle whatever the user actually meant.
                    workingMemory.remove(sessionId, "pending_entity_conflict");
                }
            }
        }

        // ---- M2-C: if a previous turn left an elicitation pending, the
        // user's current message fills the requested slot — skip the
        // intent tracker, jump straight to the dispatch we wanted last time.
        @SuppressWarnings("unchecked")
        Map<String, Object> pendingElicit = pendingElicitOf(wm);
        if (pendingElicit != null) {
            String toolId = String.valueOf(pendingElicit.get("toolId"));
            String missingField = String.valueOf(pendingElicit.get("missingField"));
            Map<String, Object> filledArgs = new java.util.HashMap<>(
                    (Map<String, Object>) pendingElicit.getOrDefault("partialArgs", Map.of()));
            filledArgs.put(missingField, message.text());
            workingMemory.remove(sessionId, "pending_elicit");

            Flux<TurnEvent> resumed = dispatchByToolId(new Ids.ToolId(toolId), filledArgs,
                    profile, personality, session, exec, recorder, turnId, message);
            if (resumed != null) {
                return resumed.concatWith(Mono.fromCallable(() -> TurnEvent.End.of(turnId, 0, 0)));
            }
            // tool descriptor went missing — drop pending and fall through.
        }

        // ---- M2-A: intent tracking + working memory --------------------
        IntentFrame previousIntent = activeIntents.getOrDefault(sessionId, session.activeIntent());
        if (previousIntent == null) previousIntent = IntentFrame.empty();
        IntentFrame updatedIntent = intentTracker.update(List.of(), message, previousIntent);
        activeIntents.put(sessionId, updatedIntent);
        if (updatedIntent.slots() != null && !updatedIntent.slots().isEmpty()) {
            workingMemory.merge(sessionId, Map.of("intent", updatedIntent.slots()));
        }
        workingMemory.merge(sessionId, Map.of(
                "last_user_message", message.text(),
                "intent_label", updatedIntent.label() == null ? "" : updatedIntent.label()));

        // ---- Cross-turn memory: load prior tail + rolling summary -------
        // The recorder owns summarization (CLAUDE.md SPI #10) — engine just
        // asks for a summarized view. Empty list / null summary on the very
        // first turn is the expected base case.
        PriorContext prior = loadPriorContext(recorder, sessionId);

        // Run the agentic loop — the LLM sees the tool catalog and either
        // proposes a tool_use (which goes through runToolDispatch with full
        // AuthZ/elicit/confirm semantics) or streams a plain reply.
        final IntentFrame currentIntent = updatedIntent;
        Flux<TurnEvent> body = streamLlmTurn(personality, profile, session,
                currentIntent, message, recorder, exec, turnId,
                prior.historyEntries, prior.tailTurns, prior.summaryText);

        return body.concatWith(Mono.fromCallable(() -> TurnEvent.End.of(turnId, 0, 0)));
    }

    /** Per-session prior context for prompt + LLM history. */
    private record PriorContext(List<LlmClient.HistoryEntry> historyEntries,
                                List<Turn> tailTurns,
                                String summaryText) {
        static PriorContext empty() {
            return new PriorContext(List.of(), List.of(), null);
        }
    }

    private PriorContext loadPriorContext(ConversationRecorder recorder, Ids.SessionId sessionId) {
        try {
            return recorder.loadSession(sessionId, LoadOptions.summarizedForPrompt(memoryConfig.tailWindow()))
                    .map(rec -> {
                        List<Turn> tail = rec.turns() == null ? List.<Turn>of() : rec.turns();
                        String summary = rec.summary() == null ? null : rec.summary().text();
                        rehydrateEntityMemoryIfEmpty(sessionId, tail);
                        return new PriorContext(toHistoryEntries(tail), tail, summary);
                    })
                    .orElse(PriorContext.empty());
        } catch (RuntimeException e) {
            // History load must never fail a user turn — degrade gracefully.
            return PriorContext.empty();
        }
    }

    /**
     * Phase-1 entity-memory rehydration. When {@link EntityMemory} has no
     * entries for this session (process restart, evicted cache, etc.),
     * walk the recorded turn results and re-capture every persisted
     * {@link EntityRef}. Phase 2 replaces this with a dedicated table read
     * — see docs/design/entity-memory-design.md §15.
     */
    private void rehydrateEntityMemoryIfEmpty(Ids.SessionId sessionId, List<Turn> tail) {
        if (entityMemory == null || tail == null || tail.isEmpty()) return;
        if (!entityMemory.all(sessionId).isEmpty()) return;
        for (Turn turn : tail) {
            if (turn.toolResults() == null) continue;
            for (Turn.ToolResult tr : turn.toolResults()) {
                if (tr.capturedEntities() == null) continue;
                for (EntityRef ref : tr.capturedEntities()) {
                    entityMemory.capture(sessionId, ref);
                }
            }
        }
    }

    /**
     * Projects recorded {@link Turn}s into the LLM client's history shape.
     * USER → {@code User}; ASSISTANT → {@code Assistant} (carrying any tool
     * calls that produced a matching result on the same turn), optionally
     * followed by a {@code ToolResults} entry for those results. SYSTEM /
     * TOOL roles are filtered — the recorder doesn't currently produce them
     * in the canonical engine path, and we don't want to invent ones.
     *
     * <p>Orphan {@code tool_use} blocks are dropped: Anthropic's API
     * requires every {@code tool_use} to be immediately followed by a
     * matching {@code tool_result}, and the confirmable-tool preview flow
     * records a {@link Turn.ToolCall} without a result (we paused for the
     * user's yes/no). Sending that turn back as history would yield
     * {@code 400 BAD_REQUEST: tool_use ids were found without tool_result
     * blocks immediately after}. The original {@link Turn.ToolCall} stays
     * in the recorded transcript for audit / playground display; we just
     * don't surface the unpaired half to the model.</p>
     */
    static List<LlmClient.HistoryEntry> toHistoryEntries(List<Turn> turns) {
        List<LlmClient.HistoryEntry> out = new java.util.ArrayList<>(turns.size());
        for (Turn t : turns) {
            switch (t.role()) {
                case USER -> out.add(new LlmClient.HistoryEntry.User(
                        t.content() == null ? "" : t.content()));
                case ASSISTANT -> {
                    List<Turn.ToolCall> rawCalls = t.toolCalls() == null ? List.of() : t.toolCalls();
                    List<Turn.ToolResult> rawResults = t.toolResults() == null ? List.of() : t.toolResults();
                    java.util.Set<String> resultIds = rawResults.stream()
                            .map(Turn.ToolResult::callId)
                            .collect(java.util.stream.Collectors.toSet());
                    java.util.Set<String> callIds = rawCalls.stream()
                            .map(Turn.ToolCall::callId)
                            .collect(java.util.stream.Collectors.toSet());

                    List<LlmClient.ToolCallSpec> calls = rawCalls.stream()
                            .filter(c -> resultIds.contains(c.callId()))
                            .map(c -> new LlmClient.ToolCallSpec(c.callId(),
                                    c.toolId().value(),
                                    c.args() == null ? Map.<String, Object>of() : c.args()))
                            .toList();
                    List<LlmClient.ToolResultSpec> results = rawResults.stream()
                            .filter(r -> callIds.contains(r.callId()))
                            .map(r -> new LlmClient.ToolResultSpec(
                                    r.callId(), r.value(),
                                    r.status() != Turn.ToolResult.Status.OK))
                            .toList();

                    String text = t.content() == null ? "" : t.content();
                    if (!text.isEmpty() || !calls.isEmpty()) {
                        out.add(new LlmClient.HistoryEntry.Assistant(text, calls));
                    }
                    if (!results.isEmpty()) {
                        out.add(new LlmClient.HistoryEntry.ToolResults(results));
                    }
                }
                case SYSTEM, TOOL -> { /* skipped — not produced by the engine */ }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pendingElicitOf(Map<String, Object> wm) {
        Object raw = wm == null ? null : wm.get("pending_elicit");
        if (raw instanceof Map<?, ?> m && !m.isEmpty()) return (Map<String, Object>) m;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pendingMapOf(Map<String, Object> wm, String key) {
        Object raw = wm == null ? null : wm.get(key);
        if (raw instanceof Map<?, ?> m && !m.isEmpty()) return (Map<String, Object>) m;
        return null;
    }

    /**
     * True when the user already confirmed this exact operation earlier in
     * this engine turn. Match is on (turnId, toolId, args fingerprint) — a
     * retry with the same args reuses consent; different args are treated
     * as a different operation and earn their own confirmation. Consent is
     * scoped to the current {@link Ids.TurnId}, so a new user turn always
     * starts from a clean slate.
     */
    private boolean alreadyConsentedThisTurn(Ids.SessionId sessionId,
                                             Ids.TurnId turnId,
                                             Ids.ToolId toolId,
                                             Map<String, Object> args) {
        Map<String, Object> consumed = pendingMapOf(workingMemory.get(sessionId), "consumed_confirm");
        if (consumed == null) return false;
        return turnId.value().equals(String.valueOf(consumed.get("turnId")))
                && toolId.value().equals(String.valueOf(consumed.get("toolId")))
                && argsFingerprint(args).equals(String.valueOf(consumed.get("argsFingerprint")));
    }

    /**
     * Deterministic fingerprint for a tool args map. Used to decide whether
     * a retry the LLM proposes after a failed dispatch is "the same
     * operation the user already consented to" or a genuinely different
     * call that still needs a fresh confirmation.
     */
    private String argsFingerprint(Map<String, Object> args) {
        try {
            return fingerprintMapper.writeValueAsString(args == null ? Map.of() : args);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Args that can't serialize cleanly cannot match anything; that
            // is the safe failure mode — it forces a fresh confirmation
            // rather than silently waving the call through.
            return "__unfingerprintable__:" + System.identityHashCode(args);
        }
    }

    /**
     * Continues a confirmable tool dispatch after the user has consented.
     * Streams the tool call + result as usual, then continues with one LLM
     * round so the model paraphrases the outcome in the conversation
     * language — instead of the hardcoded English fallback that used to
     * terminate the turn here.
     */
    private Flux<TurnEvent> resumeConfirmedDispatch(Ids.ToolId toolId,
                                                    Map<String, Object> args,
                                                    String callIdHint,
                                                    Profile profile,
                                                    PersonalityFragment personality,
                                                    Session session,
                                                    IntentFrame intent,
                                                    ExecutionContext exec,
                                                    ConversationRecorder recorder,
                                                    Ids.TurnId turnId,
                                                    UserMessage message) {
        if (catalog.tool(toolId).isEmpty()) return null;

        PriorContext prior = loadPriorContext(recorder, session.id());
        java.util.concurrent.atomic.AtomicReference<Turn.ToolResult> captured =
                new java.util.concurrent.atomic.AtomicReference<>();

        Flux<TurnEvent> dispatched = dispatchByToolId(toolId, args, profile, personality,
                session, exec, recorder, turnId, message,
                /* skipConfirm */ true, /* callIdHint */ callIdHint,
                /* llmDriven */ true)
                .doOnNext(ev -> {
                    if (ev instanceof TurnEvent.ToolResult tr) {
                        captured.set(new Turn.ToolResult(tr.callId(), tr.status(),
                                tr.value(), tr.error()));
                    }
                });

        Flux<TurnEvent> continuation = Flux.defer(() -> {
            Turn.ToolResult result = captured.get();
            if (result == null) {
                // Dispatch produced no ToolResult — nothing for the LLM to
                // paraphrase. This happens only for elicitation pauses,
                // which surface their own user-facing question.
                return Flux.<TurnEvent>empty();
            }
            List<LlmClient.HistoryEntry> history = new java.util.ArrayList<>(prior.historyEntries);
            history.add(new LlmClient.HistoryEntry.User(message.text()));
            history.add(new LlmClient.HistoryEntry.Assistant("",
                    List.of(new LlmClient.ToolCallSpec(callIdHint, toolId.value(), args))));
            history.add(new LlmClient.HistoryEntry.ToolResults(List.of(
                    new LlmClient.ToolResultSpec(callIdHint, result.value(),
                            result.status() != Turn.ToolResult.Status.OK))));

            List<RetrievedChunk> retrieved = retrieveForTurn(profile, session, intent, exec, message);
            java.util.concurrent.atomic.AtomicBoolean anyDispatch =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
            return streamLlmRound(personality, profile, session, intent, message,
                    recorder, exec, turnId, history, retrieved,
                    prior.tailTurns, prior.summaryText,
                    /* round */ 1, anyDispatch);
        });

        return dispatched.concatWith(continuation);
    }

    @Override
    public void end(Ids.SessionId sessionId, AuthContext auth) {
        Session session = active.remove(sessionId);
        activeIntents.remove(sessionId);
        nextTurnIdx.remove(sessionId);
        lastPromptBySession.remove(sessionId);
        lastRetrievalBySession.remove(sessionId);
        workingMemory.clear(sessionId);
        if (entityMemory != null) {
            entityMemory.clearScope(sessionId, com.yayatechandinnovations.yayaagentic.core.entity.EntityScope.SESSION);
        }
        if (session == null) return;
        ConversationRecorder recorder = recorderRouter.recorderFor(session,
                new RecorderRouter.RecordingDecisionContext(Map.of()));
        recorder.onSessionEnded(sessionId, new Operations.SessionEndContext(Instant.now(), "client-ended"));
    }

    @Override
    public java.util.Optional<IntentFrame> currentIntent(Ids.SessionId sessionId) {
        return java.util.Optional.ofNullable(activeIntents.get(sessionId));
    }

    @Override
    public java.util.Optional<PromptBuilder.PromptPayload> lastPrompt(Ids.SessionId sessionId) {
        return java.util.Optional.ofNullable(lastPromptBySession.get(sessionId));
    }

    @Override
    public java.util.Optional<RetrievalResult> lastRetrieval(Ids.SessionId sessionId) {
        return java.util.Optional.ofNullable(lastRetrievalBySession.get(sessionId));
    }

    // ---- Internals --------------------------------------------------

    private static final int MAX_TOOL_ROUNDS = 5;

    /**
     * The agentic turn loop. Each round calls the LLM once; if the LLM
     * proposes tools we dispatch (through the same AuthZ / schema /
     * confirmable pipeline as everything else), append the assistant +
     * tool-result pair to history, and recurse for the LLM's continuation.
     * Stops on {@code stop_reason=end_turn}, an empty round, or
     * {@link #MAX_TOOL_ROUNDS} as a safety net.
     */
    private Flux<TurnEvent> streamLlmTurn(PersonalityFragment personality,
                                          Profile profile,
                                          Session session,
                                          IntentFrame intent,
                                          UserMessage message,
                                          ConversationRecorder recorder,
                                          ExecutionContext exec,
                                          Ids.TurnId turnId,
                                          List<LlmClient.HistoryEntry> priorHistory,
                                          List<Turn> priorTurns,
                                          String earlierSummary) {
        List<LlmClient.HistoryEntry> initial = new java.util.ArrayList<>(priorHistory.size() + 1);
        initial.addAll(priorHistory);
        initial.add(new LlmClient.HistoryEntry.User(message.text()));
        java.util.concurrent.atomic.AtomicBoolean anyDispatch = new java.util.concurrent.atomic.AtomicBoolean(false);

        // M2.5 — retrieve once at the top of the turn (always-on gating).
        // The grounded chunks feed every LLM round of this turn so a
        // multi-round agentic continuation still has the same context.
        List<RetrievedChunk> retrieved = retrieveForTurn(profile, session, intent, exec, message);

        Flux<TurnEvent> citations = Flux.fromIterable(retrieved)
                .map(this::toCitationEvent);

        Flux<TurnEvent> body = streamLlmRound(personality, profile, session, intent, message,
                recorder, exec, turnId, initial, retrieved, priorTurns, earlierSummary, 0, anyDispatch);

        return citations.concatWith(body);
    }

    private List<RetrievedChunk> retrieveForTurn(Profile profile, Session session,
                                                 IntentFrame intent, ExecutionContext exec,
                                                 UserMessage message) {
        var sourceIds = catalog.sourcesForProfile(profile.id());
        if (sourceIds.isEmpty()) {
            lastRetrievalBySession.remove(session.id());
            return List.of();
        }
        RetrievalQuery query = new RetrievalQuery(message.text(), sourceIds, Map.of());
        RetrievalContext rctx = new RetrievalContext(exec, intent);
        try {
            RetrievalResult result = retriever.retrieve(query, rctx);
            lastRetrievalBySession.put(session.id(), result);
            return result.chunks() == null ? List.of() : result.chunks();
        } catch (RuntimeException e) {
            // Retrieval is a best-effort grounding step; never fail the
            // turn over an embedding hiccup. The LLM will just answer
            // without grounding and the prompt's grounding rule will
            // make it refuse facts it can't support.
            lastRetrievalBySession.remove(session.id());
            return List.of();
        }
    }

    private TurnEvent.Citation toCitationEvent(RetrievedChunk chunk) {
        Map<String, Object> meta = chunk.metadata() == null ? Map.of() : chunk.metadata();
        String title = String.valueOf(meta.getOrDefault("documentTitle",
                meta.getOrDefault("section", chunk.documentId())));
        String url = String.valueOf(meta.getOrDefault("documentUri", ""));
        return new TurnEvent.Citation(chunk.chunkId(), chunk.source(), title, url);
    }

    private Flux<TurnEvent> streamLlmRound(PersonalityFragment personality,
                                           Profile profile,
                                           Session session,
                                           IntentFrame intent,
                                           UserMessage message,
                                           ConversationRecorder recorder,
                                           ExecutionContext exec,
                                           Ids.TurnId turnId,
                                           List<LlmClient.HistoryEntry> history,
                                           List<RetrievedChunk> retrieved,
                                           List<Turn> priorTurns,
                                           String earlierSummary,
                                           int round,
                                           java.util.concurrent.atomic.AtomicBoolean anyDispatch) {
        if (round >= MAX_TOOL_ROUNDS) {
            String warning = "(stopped — tool-call depth limit reached)";
            return Flux.<TurnEvent>just(new TurnEvent.Token(warning))
                    .doOnComplete(() -> recordTurns(recorder, session, exec, turnId, message,
                            warning, List.of(), List.of()));
        }

        List<LlmClient.ToolDefinition> available = buildAvailableTools(profile);
        PromptBuilder.PromptPayload payload = promptBuilder.build(
                personality, profile, session, intent,
                priorTurns == null ? List.of() : priorTurns,
                retrieved, message, earlierSummary);
        // Keep the most recent payload so the inspector endpoint can show
        // exactly what was sent — cacheable prefix vs variable suffix split.
        lastPromptBySession.put(session.id(), payload);
        LlmClient.LlmRequest request = new LlmClient.LlmRequest(
                payload.cacheablePrefix(), payload.variableSuffix(), history, available);

        StringBuilder roundText = new StringBuilder();
        java.util.List<LlmClient.ToolCallSpec> roundCalls = new java.util.ArrayList<>();
        java.util.List<LlmClient.ToolResultSpec> roundResults = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<LlmClient.StopReason> stopReason =
                new java.util.concurrent.atomic.AtomicReference<>(LlmClient.StopReason.END_TURN);

        return llmClient.stream(request)
                .concatMap(event -> switch (event) {
                    case LlmClient.LlmEvent.TokenChunk(String text) -> {
                        roundText.append(text);
                        yield Flux.<TurnEvent>just(new TurnEvent.Token(text));
                    }
                    case LlmClient.LlmEvent.ToolUseProposal(String callId,
                                                            String toolName,
                                                            Map<String, Object> args) -> {
                        anyDispatch.set(true);
                        roundCalls.add(new LlmClient.ToolCallSpec(callId, toolName, args));
                        Ids.ToolId toolId = new Ids.ToolId(toolName);
                        // Pass the LLM-assigned call id through so the
                        // dispatched ToolResult carries the same id Anthropic
                        // expects in continuation rounds. llmDriven=true so a
                        // denial doesn't emit a hardcoded refusal — the next
                        // round paraphrases it from the DENIED tool_result.
                        Flux<TurnEvent> dispatched = dispatchByToolId(toolId, args,
                                profile, personality, session, exec, recorder, turnId, message,
                                /* skipConfirm */ false, /* callIdHint */ callId,
                                /* llmDriven */ true);
                        if (dispatched == null) {
                            yield Flux.<TurnEvent>just(new TurnEvent.Token(
                                    "(unknown tool: " + toolName + ")"));
                        }
                        // Tap dispatched events to capture tool_result for the
                        // continuation history. Other events flow through verbatim.
                        yield dispatched.doOnNext(ev -> {
                            if (ev instanceof TurnEvent.ToolResult tr) {
                                roundResults.add(new LlmClient.ToolResultSpec(
                                        tr.callId(), tr.value(),
                                        tr.status() != Turn.ToolResult.Status.OK));
                            }
                        });
                    }
                    case LlmClient.LlmEvent.Done done -> {
                        stopReason.set(done.stopReason() == null
                                ? LlmClient.StopReason.END_TURN : done.stopReason());
                        yield Flux.<TurnEvent>empty();
                    }
                })
                .concatWith(Flux.defer(() -> {
                    // Only continue the loop when the LLM proposed a tool AND a
                    // result actually came back. Elicitation and confirm flows
                    // emit NO ToolResult — they are user-pause states; ending
                    // the turn there is correct.
                    boolean continueLoop = stopReason.get() == LlmClient.StopReason.TOOL_USE
                            && !roundCalls.isEmpty()
                            && !roundResults.isEmpty();
                    if (continueLoop) {
                        List<LlmClient.HistoryEntry> next = new java.util.ArrayList<>(history);
                        next.add(new LlmClient.HistoryEntry.Assistant(
                                roundText.toString(), List.copyOf(roundCalls)));
                        if (!roundResults.isEmpty()) {
                            next.add(new LlmClient.HistoryEntry.ToolResults(List.copyOf(roundResults)));
                        }
                        return streamLlmRound(personality, profile, session, intent, message,
                                recorder, exec, turnId, next, retrieved, priorTurns, earlierSummary,
                                round + 1, anyDispatch);
                    }
                    // Terminal round.
                    // - If no tool ever fired this user turn, record the
                    //   assistant text turn. (Dispatch flows own their own
                    //   recording; we don't double-write.)
                    // - If tools fired AND the LLM produced text after them,
                    //   that text is in the stream but isn't currently recorded
                    //   to the conversation log — M3 observability work cleans
                    //   that up.
                    if (!anyDispatch.get() && !roundText.toString().isBlank()) {
                        recordTurns(recorder, session, exec, turnId, message,
                                roundText.toString(), List.of(), List.of());
                    }
                    return Flux.<TurnEvent>empty();
                }));
    }

    /** Projects the profile's reachable tool descriptors into the wire
     *  shape the LLM consumes. Description = capability llmGuidance. */
    private List<LlmClient.ToolDefinition> buildAvailableTools(Profile profile) {
        List<LlmClient.ToolDefinition> out = new java.util.ArrayList<>();
        if (profile == null || profile.capabilities() == null) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Ids.CapabilityId capId : profile.capabilities()) {
            catalog.capability(capId).ifPresent(cap -> {
                String description = cap.llmGuidance() == null
                        ? cap.userFacingDescription() : cap.llmGuidance();
                for (Ids.ToolId toolId : cap.tools()) {
                    if (!seen.add(toolId.value())) continue;
                    catalog.tool(toolId).ifPresent(t -> out.add(new LlmClient.ToolDefinition(
                            t.id().value(),
                            description == null ? "" : description,
                            t.inputSchemaJson())));
                }
            });
        }
        return out;
    }

    /** Resumes a tool dispatch when a prior elicitation has been satisfied. */
    private Flux<TurnEvent> dispatchByToolId(Ids.ToolId toolId, Map<String, Object> args,
                                             Profile profile, PersonalityFragment personality,
                                             Session session, ExecutionContext exec,
                                             ConversationRecorder recorder, Ids.TurnId turnId,
                                             UserMessage message) {
        return dispatchByToolId(toolId, args, profile, personality, session, exec,
                recorder, turnId, message, /* skipConfirm */ false,
                /* callIdHint */ null, /* llmDriven */ false);
    }

    private Flux<TurnEvent> dispatchByToolId(Ids.ToolId toolId, Map<String, Object> args,
                                             Profile profile, PersonalityFragment personality,
                                             Session session, ExecutionContext exec,
                                             ConversationRecorder recorder, Ids.TurnId turnId,
                                             UserMessage message, boolean skipConfirm) {
        return dispatchByToolId(toolId, args, profile, personality, session, exec,
                recorder, turnId, message, skipConfirm,
                /* callIdHint */ null, /* llmDriven */ false);
    }

    private Flux<TurnEvent> dispatchByToolId(Ids.ToolId toolId, Map<String, Object> args,
                                             Profile profile, PersonalityFragment personality,
                                             Session session, ExecutionContext exec,
                                             ConversationRecorder recorder, Ids.TurnId turnId,
                                             UserMessage message, boolean skipConfirm,
                                             String callIdHint, boolean llmDriven) {
        if (catalog.tool(toolId).isEmpty()) return null;
        return runToolDispatch(toolId, args, profile, personality, session, exec,
                recorder, turnId, message, skipConfirm, callIdHint, llmDriven);
    }

    /**
     * Single tool-dispatch path used by intent-driven, elicitation-resumed,
     * and confirm-resumed flows. Handles AuthZ, ToolPolicy.confirmable,
     * schema validation, and the three terminal cases:
     * Dispatched/OK, Dispatched/DENIED, NeedsInput.
     */
    private Flux<TurnEvent> runToolDispatch(Ids.ToolId toolId,
                                            Map<String, Object> args,
                                            Profile profile,
                                            PersonalityFragment personality,
                                            Session session,
                                            ExecutionContext exec,
                                            ConversationRecorder recorder,
                                            Ids.TurnId turnId,
                                            UserMessage message,
                                            boolean skipConfirm,
                                            String callIdHint,
                                            boolean llmDriven) {
        ToolDescriptor toolDescriptor = catalog.tool(toolId)
                .orElseThrow(() -> new IllegalStateException("tool descriptor missing: " + toolId.value()));

        // M2.10 — apply entity bindings BEFORE authorize so the chain sees
        // the real expanded args (the actual ticketId / principalSubject,
        // not a placeholder the LLM omitted). Bindings are a no-op when the
        // tool has no declared bindings or memory has no matching entities.
        EntityBinder.Result bindResult = EntityBinder.apply(
                args, toolDescriptor.bindings(), entityMemory, session.id());
        Map<String, Object> effectiveArgs = bindResult.args();

        AuthzDecision decision = authorizer.authorize(
                session.principal(), toolDescriptor.requires(), effectiveArgs,
                new AuthzContext(session.id(), turnId, exec.traceId(),
                        Map.of("toolId", toolDescriptor.id().value(),
                                "boundEntities", bindResult.bound())));

        // W-A5: DenyOfferStepUp goes through the coordinator first. If the
        // coordinator mounts a Pending verification cycle, the turn ends
        // with a step-up prompt and the next inbound (the user's OTP) is
        // intercepted BEFORE this code path runs again. Cool-off or
        // unrecoverable provider failures fall through to denialFlow with a
        // user-safe reason. Plain Deny always routes to denialFlow.
        if (decision instanceof AuthzDecision.DenyOfferStepUp stepUp) {
            return stepUpFlow(toolDescriptor, effectiveArgs, stepUp, personality, session,
                    exec, recorder, turnId, message, callIdHint, llmDriven);
        }
        if (decision.isDenied()) {
            return denialFlow(toolDescriptor, effectiveArgs, decision, personality, session, exec,
                    recorder, turnId, message, callIdHint, llmDriven);
        }

        // B2.8 — confirmable tools pause for explicit confirm before dispatch.
        // Exception: if the user already consented to this exact (tool,
        // args) operation earlier in this engine turn — e.g. the LLM is
        // proposing a retry after a failed dispatch — don't loop them back
        // through the same yes/no. Different args still earn a fresh
        // confirmation; see alreadyConsentedThisTurn for the match rule.
        // Consent matching uses the EXPANDED args so a retry whose
        // post-binding shape matches the prior consent reuses it.
        if (!skipConfirm && toolDescriptor.policy() != null && toolDescriptor.policy().confirmable()
                && !alreadyConsentedThisTurn(session.id(), turnId, toolDescriptor.id(), effectiveArgs)) {
            return confirmFlow(toolDescriptor, effectiveArgs, personality, session, exec, recorder, turnId, message);
        }

        // Hop off the Reactor Netty event loop before executing. HTTP tool
        // dispatch is synchronous-blocking inside (WebClient + mono.block in
        // HttpToolDispatcher), and running that on the event loop deadlocks
        // the response read and trips BlockHound. boundedElastic is the
        // standard scheduler for blocking I/O bridges.
        return Mono.fromCallable(() -> toolExecutor.execute(toolDescriptor, effectiveArgs, exec, callIdHint))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMapMany(outcome -> switch (outcome) {
                    case ToolExecutor.Outcome.NeedsInput ni ->
                            elicitationFlow(toolDescriptor, effectiveArgs, ni, personality, session, exec, recorder, turnId, message);
                    case ToolExecutor.Outcome.Dispatched(var result) ->
                            dispatchedFlow(toolDescriptor, effectiveArgs, result, profile, session, exec, recorder, turnId, message, llmDriven);
                });
    }

    /** B2.8 — Two-phase preview: stream tool_call + UiHint(confirm) and wait for the next turn. */
    private Flux<TurnEvent> confirmFlow(ToolDescriptor toolDescriptor,
                                        Map<String, Object> args,
                                        PersonalityFragment personality,
                                        Session session,
                                        ExecutionContext exec,
                                        ConversationRecorder recorder,
                                        Ids.TurnId turnId,
                                        UserMessage message) {
        String callId = UUID.randomUUID().toString();
        String summary = "Run " + toolDescriptor.id().value() + " with " + args + "?";
        Map<String, Object> hintPayload = Map.of(
                "toolId", toolDescriptor.id().value(),
                "callId", callId,
                "args", args,
                "summary", summary);

        workingMemory.merge(session.id(), Map.of("pending_confirm", Map.of(
                "toolId", toolDescriptor.id().value(),
                "args", args,
                "callId", callId)));

        String spoken = summary + " (reply yes or no)";
        return Flux.<TurnEvent>just(
                        new TurnEvent.ToolCall(callId, toolDescriptor.id(), args),
                        new TurnEvent.UiHint("confirm", hintPayload),
                        new TurnEvent.Token(spoken))
                .doOnComplete(() -> recordTurns(recorder, session, exec, turnId, message, spoken,
                        List.of(new Turn.ToolCall(callId, toolDescriptor.id(), args)), List.of()));
    }

    private Flux<TurnEvent> dispatchedFlow(ToolDescriptor toolDescriptor,
                                           Map<String, Object> args,
                                           Turn.ToolResult result,
                                           Profile profile,
                                           Session session,
                                           ExecutionContext exec,
                                           ConversationRecorder recorder,
                                           Ids.TurnId turnId,
                                           UserMessage message,
                                           boolean llmDriven) {
        Turn.ToolCall call = new Turn.ToolCall(result.callId(), toolDescriptor.id(), args);

        // M2.10 — extract declared captures and either commit them to
        // EntityMemory or queue an ASK_USER conflict for the next turn.
        // Only OK results capture: DENIED/FAILED would let a poisoned
        // upstream response pollute memory through a denied path.
        CaptureOutcome captureOutcome = (result.status() == Turn.ToolResult.Status.OK)
                ? applyCaptures(toolDescriptor, result, session)
                : CaptureOutcome.empty();
        Turn.ToolResult recordedResult = result.withCapturedEntities(captureOutcome.captured());

        List<String> followUps = (result.status() == Turn.ToolResult.Status.OK)
                ? followUpHintsForTool(profile, toolDescriptor.id())
                : List.of();

        List<TurnEvent> events = new java.util.ArrayList<>(5);
        events.add(new TurnEvent.ToolCall(call.callId(), toolDescriptor.id(), args));
        events.add(new TurnEvent.ToolResult(call.callId(), recordedResult.status(),
                recordedResult.value(), recordedResult.error()));
        if (captureOutcome.conflictUiHint() != null) {
            events.add(captureOutcome.conflictUiHint());
        }

        if (llmDriven) {
            // The continuation LLM round paraphrases the tool result in the
            // profile's language. Don't emit a hardcoded English Token here
            // — that bypasses the language directive in the prompt prefix.
            // Follow-up quick replies are still useful as UI affordances.
            if (!followUps.isEmpty()) {
                events.add(new TurnEvent.UiHint("quick_replies", Map.of("items", followUps)));
            }
            return Flux.fromIterable(events)
                    .doOnComplete(() -> recordTurns(recorder, session, exec, turnId, message, "",
                            List.of(call), List.of(recordedResult)));
        }

        // Resumed (confirm / elicit) paths don't get a fresh LLM round, so
        // they still need a fallback reply. Hardcoded English here matches
        // the equivalent denialFlow branch.
        String spoken = switch (recordedResult.status()) {
            case OK -> "You said: \""
                    + String.valueOf(args.getOrDefault("text", "")) + "\". Anything else?";
            case DENIED -> "I can't echo that.";
            case FAILED -> "Sorry — that didn't work (" + recordedResult.error() + ").";
        };
        events.add(new TurnEvent.Token(spoken));
        if (!followUps.isEmpty()) {
            events.add(new TurnEvent.UiHint("quick_replies", Map.of("items", followUps)));
        }

        return Flux.fromIterable(events)
                .doOnComplete(() -> recordTurns(recorder, session, exec, turnId, message, spoken,
                        List.of(call), List.of(recordedResult)));
    }

    /**
     * Walks the tool's declared captures, extracts each value via JSON
     * Pointer, and either commits it to {@link EntityMemory} or queues an
     * {@link OnConflict#ASK_USER} resolution. Returns the list of
     * successfully-committed {@link EntityRef}s (for recording) and an
     * optional conflict {@link TurnEvent.UiHint} to emit.
     *
     * <p>Phase 1 surfaces at most ONE conflict per call. If multiple
     * captures conflict, the first one wins and the rest are deferred to
     * the next dispatch — they'll re-evaluate after the user resolves the
     * first conflict.</p>
     */
    private CaptureOutcome applyCaptures(ToolDescriptor toolDescriptor,
                                         Turn.ToolResult result,
                                         Session session) {
        List<EntityCapture> captures = toolDescriptor.captures();
        if (captures == null || captures.isEmpty()) return CaptureOutcome.empty();

        List<EntityRef> committed = new java.util.ArrayList<>(captures.size());
        TurnEvent.UiHint conflictHint = null;

        for (EntityCapture capture : captures) {
            Object extracted = entityExtractor.extract(result.value(), capture.jsonPointer());
            if (extracted == null) continue;

            EntityRef incoming = new EntityRef(
                    capture.name(),
                    extracted,
                    capture.scope(),
                    capture.kind(),
                    capture.visibleToLlm(),
                    toolDescriptor.id(),
                    Instant.now(),
                    Map.of());

            // Collection captures append unconditionally (no SINGLETON
            // conflict semantics apply). EntityMemory handles the merge.
            if (capture.kind() == EntityKind.COLLECTION) {
                entityMemory.capture(session.id(), incoming);
                committed.add(incoming);
                continue;
            }

            // SINGLETON — check for existing under the same name.
            Optional<EntityRef> existing = entityMemory.resolve(session.id(), capture.name());
            if (existing.isEmpty()) {
                entityMemory.capture(session.id(), incoming);
                committed.add(incoming);
                continue;
            }

            switch (capture.onConflict()) {
                case OVERWRITE -> {
                    entityMemory.capture(session.id(), incoming);
                    committed.add(incoming);
                }
                case IGNORE -> { /* keep existing; do nothing */ }
                case ERROR -> {
                    // Surface as a typed failure on the recorded result.
                    // For phase 1 we just skip the capture and let the
                    // FAILED status (if upstream sets it later) handle it.
                    // ERROR is operator-opt-in; ASK_USER is the default.
                }
                case ASK_USER -> {
                    if (conflictHint != null) continue; // only one at a time
                    workingMemory.merge(session.id(), Map.of("pending_entity_conflict", Map.of(
                            "entityName", capture.name(),
                            "incoming", incoming,
                            "existing", existing.get(),
                            "rollbackTool", capture.rollbackTool().map(Ids.ToolId::value).orElse(""),
                            "describeTool", capture.describeTool().map(Ids.ToolId::value).orElse(""))));
                    conflictHint = new TurnEvent.UiHint("entity_conflict", Map.of(
                            "name", capture.name(),
                            "existing", Map.of(
                                    "producedBy", existing.get().producedBy() == null
                                            ? "" : existing.get().producedBy().value(),
                                    "capturedAt", existing.get().capturedAt().toString()),
                            "options", List.of("continue_existing", "replace", "view_existing")));
                }
                case APPEND -> { /* invariant prevented at descriptor build */ }
            }
        }

        return new CaptureOutcome(List.copyOf(committed), conflictHint);
    }

    /**
     * Captures successfully committed in this dispatch plus, optionally, a
     * single {@link TurnEvent.UiHint} to surface a pending entity conflict.
     */
    private record CaptureOutcome(List<EntityRef> captured, TurnEvent.UiHint conflictUiHint) {
        static CaptureOutcome empty() {
            return new CaptureOutcome(List.of(), null);
        }
    }

    /**
     * REPLACE branch of the entity-conflict resolver. Dispatches the
     * operator-declared rollback tool against the existing entity (which
     * is still in memory at this point; the rollback tool's bindings fill
     * its args). On rollback success, captures the incoming entity; the
     * LLM continuation paraphrases the outcome.
     */
    private Flux<TurnEvent> resumeReplaceWithRollback(Ids.ToolId rollbackToolId,
                                                      EntityRef incoming,
                                                      Profile profile,
                                                      PersonalityFragment personality,
                                                      Session session,
                                                      IntentFrame intent,
                                                      ExecutionContext exec,
                                                      ConversationRecorder recorder,
                                                      Ids.TurnId turnId,
                                                      UserMessage message) {
        if (catalog.tool(rollbackToolId).isEmpty()) return null;

        PriorContext prior = loadPriorContext(recorder, session.id());
        String callIdHint = UUID.randomUUID().toString();
        java.util.concurrent.atomic.AtomicReference<Turn.ToolResult> captured =
                new java.util.concurrent.atomic.AtomicReference<>();

        Flux<TurnEvent> dispatched = dispatchByToolId(rollbackToolId, Map.of(),
                profile, personality, session, exec, recorder, turnId, message,
                /* skipConfirm */ true, callIdHint, /* llmDriven */ true)
                .doOnNext(ev -> {
                    if (ev instanceof TurnEvent.ToolResult tr) {
                        captured.set(new Turn.ToolResult(tr.callId(), tr.status(),
                                tr.value(), tr.error()));
                    }
                });

        Flux<TurnEvent> continuation = Flux.defer(() -> {
            Turn.ToolResult result = captured.get();
            if (result == null) return Flux.<TurnEvent>empty();
            if (result.status() == Turn.ToolResult.Status.OK && incoming != null) {
                // Rollback succeeded; the existing entity has been
                // invalidated upstream. Commit the incoming entity now.
                entityMemory.capture(session.id(), incoming);
            }
            // Either way, let the LLM continuation explain what happened.
            List<LlmClient.HistoryEntry> history = new java.util.ArrayList<>(prior.historyEntries);
            history.add(new LlmClient.HistoryEntry.User(message.text()));
            history.add(new LlmClient.HistoryEntry.Assistant("",
                    List.of(new LlmClient.ToolCallSpec(callIdHint, rollbackToolId.value(), Map.of()))));
            history.add(new LlmClient.HistoryEntry.ToolResults(List.of(
                    new LlmClient.ToolResultSpec(callIdHint, result.value(),
                            result.status() != Turn.ToolResult.Status.OK))));

            List<RetrievedChunk> retrieved = retrieveForTurn(profile, session, intent, exec, message);
            java.util.concurrent.atomic.AtomicBoolean anyDispatch =
                    new java.util.concurrent.atomic.AtomicBoolean(true);
            return streamLlmRound(personality, profile, session, intent, message,
                    recorder, exec, turnId, history, retrieved,
                    prior.tailTurns, prior.summaryText,
                    /* round */ 1, anyDispatch);
        });

        return dispatched.concatWith(continuation);
    }

    /** Finds the first capability the profile references that backs this tool,
     *  then returns its {@code followUpHints}. Empty list if no match. */
    private List<String> followUpHintsForTool(Profile profile, Ids.ToolId toolId) {
        if (profile == null) return List.of();
        for (Ids.CapabilityId capId : profile.capabilities()) {
            var maybe = catalog.capability(capId);
            if (maybe.isEmpty()) continue;
            var cap = maybe.get();
            if (cap.tools() == null || !cap.tools().contains(toolId)) continue;
            return cap.followUpHints() == null ? List.of() : cap.followUpHints();
        }
        return List.of();
    }

    /** B2.2 — Convert missing-required-field into a single focused question. */
    private Flux<TurnEvent> elicitationFlow(ToolDescriptor toolDescriptor,
                                            Map<String, Object> partialArgs,
                                            ToolExecutor.Outcome.NeedsInput ni,
                                            PersonalityFragment personality,
                                            Session session,
                                            ExecutionContext exec,
                                            ConversationRecorder recorder,
                                            Ids.TurnId turnId,
                                            UserMessage message) {
        String missingField = ni.missingFields().get(0);
        String missingPrefix = personality != null && personality.refusals() != null
                && personality.refusals().missingInformation() != null
                ? personality.refusals().missingInformation() : "I need one more thing — ";
        String question = missingPrefix + "what's the " + missingField + "?";

        // Stash the partial args + which tool we were trying to call so the
        // next turn can fill the slot and resume dispatch directly.
        workingMemory.merge(session.id(), Map.of("pending_elicit", Map.of(
                "toolId", toolDescriptor.id().value(),
                "missingField", missingField,
                "partialArgs", partialArgs)));

        return Flux.<TurnEvent>just(new TurnEvent.Token(question))
                .doOnComplete(() -> recordTurns(recorder, session, exec, turnId, message, question,
                        List.of(), List.of()));
    }

    /**
     * B2.3 — Synthetic tool_result event for an AuthZ denial.
     * <p>
     * Two modes:
     * <ul>
     *   <li><b>LLM-driven</b> ({@code llmDriven=true}): emit only the
     *       structured {@code tool_call} + {@code tool_result(DENIED)}. NO
     *       hardcoded Token. The continuation LLM round sees the DENIED
     *       result in history and paraphrases naturally — answering the user
     *       in its own voice while the policy reason still rides in the
     *       structured payload for audit and UI.</li>
     *   <li><b>Resumed paths</b> ({@code llmDriven=false}): used when the
     *       engine resolves a pending elicitation or confirm itself without
     *       a fresh LLM round. The personality template provides the spoken
     *       refusal so the user still gets a reply.</li>
     * </ul>
     * The {@code callIdHint} preserves the LLM's tool_use id so Anthropic's
     * continuation round matches results to calls correctly.
     */
    /**
     * W-A5 step-up: mount a verification cycle via {@link
     * com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpCoordinator}.
     * On {@code Pending} outcome, end the turn with a step-up prompt — the
     * next inbound is the user's OTP, which the worker intercepts BEFORE the
     * engine runs. On {@code CoolOff} or {@code Unrecoverable}, fall through
     * to {@link #denialFlow} so the user gets a normal refusal.
     */
    private Flux<TurnEvent> stepUpFlow(ToolDescriptor toolDescriptor,
                                       Map<String, Object> args,
                                       AuthzDecision.DenyOfferStepUp stepUp,
                                       PersonalityFragment personality,
                                       Session session,
                                       ExecutionContext exec,
                                       ConversationRecorder recorder,
                                       Ids.TurnId turnId,
                                       UserMessage message,
                                       String callIdHint,
                                       boolean llmDriven) {
        String email = stringClaim(session.principal(), "email");
        if (email == null) {
            // No deliverable address — fall through to denial. The user-safe
            // reason from the original DenyOfferStepUp explains why; we don't
            // promise step-up.
            return denialFlow(toolDescriptor, args, stepUp, personality, session, exec,
                    recorder, turnId, message, callIdHint, llmDriven);
        }

        var outcome = stepUpCoordinator.handleDeny(
                new com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpCoordinator.HandleContext(
                        session.tenant(),
                        session.id(),
                        null,                                  // sender id — channel-bound calls fill this in W-A6
                        session.principal().subject(),
                        email,
                        maskEmail(email),
                        stepUp.userSafeReason(),
                        stepUp.hint()));

        return switch (outcome) {
            case com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpCoordinator.HandleOutcome
                    .Pending p -> emitStepUpPrompt(toolDescriptor, args, p, session, exec, recorder,
                    turnId, message, callIdHint);
            case com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpCoordinator.HandleOutcome
                    .CoolOff c -> denialFlow(toolDescriptor, args,
                    new AuthzDecision.Deny(c.userSafeReason(), "step_up_cool_off"),
                    personality, session, exec, recorder, turnId, message, callIdHint, llmDriven);
            case com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpCoordinator.HandleOutcome
                    .Unrecoverable u -> denialFlow(toolDescriptor, args,
                    new AuthzDecision.Deny(u.userSafeReason(), "step_up_unrecoverable"),
                    personality, session, exec, recorder, turnId, message, callIdHint, llmDriven);
        };
    }

    private Flux<TurnEvent> emitStepUpPrompt(ToolDescriptor toolDescriptor,
                                             Map<String, Object> args,
                                             com.yayatechandinnovations.yayaagentic.auth.stepup.StepUpCoordinator.HandleOutcome.Pending p,
                                             Session session,
                                             ExecutionContext exec,
                                             ConversationRecorder recorder,
                                             Ids.TurnId turnId,
                                             UserMessage message,
                                             String callIdHint) {
        String callId = (callIdHint == null || callIdHint.isBlank())
                ? UUID.randomUUID().toString() : callIdHint;
        // ToolResult marked DENIED with a step_up: true marker — the LLM-side
        // never sees a continuation round this turn, so the marker is for
        // audit / replay (W-A9 admin timeline). The user-visible text is the
        // separate Token below.
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("reason", "step_up_required");
        payload.put("step_up", true);
        payload.put("masked_target", p.maskedTargetAddress());
        payload.put("pending_id", p.pendingId());
        payload.put("audit_id", p.auditId());

        Turn.ToolCall recordedCall = new Turn.ToolCall(callId, toolDescriptor.id(), args);
        Turn.ToolResult recordedResult = new Turn.ToolResult(
                callId, Turn.ToolResult.Status.DENIED, payload, null);

        String spoken = p.userPromptHint();

        return Flux.<TurnEvent>just(
                        new TurnEvent.ToolCall(callId, toolDescriptor.id(), args),
                        new TurnEvent.ToolResult(callId, Turn.ToolResult.Status.DENIED, payload, null),
                        new TurnEvent.Token(spoken))
                .doOnComplete(() -> recordTurns(recorder, session, exec, turnId, message, spoken,
                        List.of(recordedCall), List.of(recordedResult)));
    }

    private static String stringClaim(Principal p, String key) {
        if (p == null || p.claims() == null) return null;
        Object v = p.claims().get(key);
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    private static String maskEmail(String email) {
        if (email == null) return "***";
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at < 0 ? "" : email.substring(at));
        return email.charAt(0) + "***" + email.substring(at);
    }

    private Flux<TurnEvent> denialFlow(ToolDescriptor toolDescriptor,
                                       Map<String, Object> args,
                                       AuthzDecision deny,
                                       PersonalityFragment personality,
                                       Session session,
                                       ExecutionContext exec,
                                       ConversationRecorder recorder,
                                       Ids.TurnId turnId,
                                       UserMessage message,
                                       String callIdHint,
                                       boolean llmDriven) {
        if (!deny.isDenied()) {
            throw new IllegalArgumentException(
                    "denialFlow called with non-deny decision: " + deny.getClass().getSimpleName());
        }
        String callId = (callIdHint == null || callIdHint.isBlank())
                ? UUID.randomUUID().toString() : callIdHint;
        Map<String, Object> denialPayload = Map.of(
                "reason", deny.userSafeReason(),
                "suggest_alternatives", true);

        Turn.ToolCall recordedCall = new Turn.ToolCall(callId, toolDescriptor.id(), args);
        Turn.ToolResult recordedResult = new Turn.ToolResult(
                callId, Turn.ToolResult.Status.DENIED, denialPayload, null);

        if (llmDriven) {
            // No spoken Token here — the next LLM round will paraphrase the
            // DENIED tool_result. Record the structured call+result with an
            // empty assistantText (audit captures what was attempted; the
            // paraphrased reply belongs to the continuation round).
            return Flux.<TurnEvent>just(
                            new TurnEvent.ToolCall(callId, toolDescriptor.id(), args),
                            new TurnEvent.ToolResult(callId, Turn.ToolResult.Status.DENIED, denialPayload, null))
                    .doOnComplete(() -> recordTurns(recorder, session, exec, turnId, message, "",
                            List.of(recordedCall), List.of(recordedResult)));
        }

        String spoken = (personality != null && personality.refusals() != null
                && personality.refusals().authorizationDenied() != null
                ? personality.refusals().authorizationDenied()
                : "I can't do that with your current access.")
                + " " + deny.userSafeReason() + ".";

        return Flux.<TurnEvent>just(
                        new TurnEvent.ToolCall(callId, toolDescriptor.id(), args),
                        new TurnEvent.ToolResult(callId, Turn.ToolResult.Status.DENIED, denialPayload, null),
                        new TurnEvent.Token(spoken))
                .doOnComplete(() -> recordTurns(recorder, session, exec, turnId, message, spoken,
                        List.of(recordedCall), List.of(recordedResult)));
    }

    private void recordTurns(ConversationRecorder recorder, Session session, ExecutionContext exec,
                             Ids.TurnId turnId, UserMessage userMessage, String assistantText,
                             List<Turn.ToolCall> calls, List<Turn.ToolResult> results) {
        Instant now = Instant.now();
        RecorderContext rc = ctx(session.tenant(), session.principal());

        var counter = nextTurnIdx.computeIfAbsent(session.id(),
                k -> new java.util.concurrent.atomic.AtomicInteger(0));
        int userIdx = counter.getAndIncrement();
        int assistantIdx = counter.getAndIncrement();

        recorder.onTurnRecorded(session.id(),
                new Turn(new Ids.TurnId(UUID.randomUUID().toString()), session.id(), userIdx,
                        Turn.Role.USER, userMessage.text(), List.of(), List.of(), List.of(),
                        null, Map.of(), now),
                rc);
        recorder.onTurnRecorded(session.id(),
                new Turn(turnId, session.id(), assistantIdx,
                        Turn.Role.ASSISTANT, assistantText, calls, results, List.of(),
                        new Turn.ModelInfo("stub-or-anthropic", "m0", 0, 0), Map.of(), now),
                rc);
    }

    private RecorderContext ctx(Ids.TenantId tenant, Principal principal) {
        return new RecorderContext(tenant, principal, UUID.randomUUID().toString(), Map.of());
    }
}
