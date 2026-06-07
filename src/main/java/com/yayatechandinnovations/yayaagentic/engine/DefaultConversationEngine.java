package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.auth.*;
import com.yayatechandinnovations.yayaagentic.core.*;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.HelloWorldProfileBootstrap;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
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
import com.yayatechandinnovations.yayaagentic.recorder.Operations;
import com.yayatechandinnovations.yayaagentic.recorder.RecorderContext;
import com.yayatechandinnovations.yayaagentic.recorder.RecorderRouter;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolExecutor;
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
                                     Retriever retriever) {
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
    }

    @Override
    public StartSessionResult start(StartConversationRequest req, AuthContext auth) {
        Principal principal = authenticator.authenticate(auth);

        Ids.ProfileId profileId = resolverChain.resolve(req, auth)
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
                    workingMemory.remove(sessionId, "pending_confirm");
                    Flux<TurnEvent> resumed = dispatchByToolId(new Ids.ToolId(toolId), args,
                            profile, personality, session, exec, recorder, turnId, message,
                            /* skipConfirm */ true);
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

        // Run the agentic loop — the LLM sees the tool catalog and either
        // proposes a tool_use (which goes through runToolDispatch with full
        // AuthZ/elicit/confirm semantics) or streams a plain reply.
        final IntentFrame currentIntent = updatedIntent;
        Flux<TurnEvent> body = streamLlmTurn(personality, profile, session,
                currentIntent, message, recorder, exec, turnId);

        return body.concatWith(Mono.fromCallable(() -> TurnEvent.End.of(turnId, 0, 0)));
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

    @Override
    public void end(Ids.SessionId sessionId, AuthContext auth) {
        Session session = active.remove(sessionId);
        activeIntents.remove(sessionId);
        nextTurnIdx.remove(sessionId);
        lastPromptBySession.remove(sessionId);
        lastRetrievalBySession.remove(sessionId);
        workingMemory.clear(sessionId);
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
                                          Ids.TurnId turnId) {
        List<LlmClient.HistoryEntry> initial = new java.util.ArrayList<>();
        initial.add(new LlmClient.HistoryEntry.User(message.text()));
        java.util.concurrent.atomic.AtomicBoolean anyDispatch = new java.util.concurrent.atomic.AtomicBoolean(false);

        // M2.5 — retrieve once at the top of the turn (always-on gating).
        // The grounded chunks feed every LLM round of this turn so a
        // multi-round agentic continuation still has the same context.
        List<RetrievedChunk> retrieved = retrieveForTurn(profile, session, intent, exec, message);

        Flux<TurnEvent> citations = Flux.fromIterable(retrieved)
                .map(this::toCitationEvent);

        Flux<TurnEvent> body = streamLlmRound(personality, profile, session, intent, message,
                recorder, exec, turnId, initial, retrieved, 0, anyDispatch);

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
                personality, profile, session, intent, List.of(), retrieved, message);
        // Keep the most recent payload so the inspector endpoint can show
        // exactly what was sent — cacheable prefix vs variable suffix split.
        lastPromptBySession.put(session.id(), payload);
        LlmClient.LlmRequest request = new LlmClient.LlmRequest(
                payload.cacheablePrefix(), payload.variableSuffix(), history, available);

        StringBuilder roundText = new StringBuilder();
        java.util.List<LlmClient.ToolCallSpec> roundCalls = new java.util.ArrayList<>();
        java.util.List<LlmClient.ToolResultSpec> roundResults = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<String> stopReason =
                new java.util.concurrent.atomic.AtomicReference<>("end_turn");

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
                        stopReason.set(done.stopReason() == null ? "end_turn" : done.stopReason());
                        yield Flux.<TurnEvent>empty();
                    }
                })
                .concatWith(Flux.defer(() -> {
                    // Only continue the loop when the LLM proposed a tool AND a
                    // result actually came back. Elicitation and confirm flows
                    // emit NO ToolResult — they are user-pause states; ending
                    // the turn there is correct.
                    boolean continueLoop = "tool_use".equals(stopReason.get())
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
                                recorder, exec, turnId, next, retrieved, round + 1, anyDispatch);
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

        AuthzDecision decision = authorizer.authorize(
                session.principal(), toolDescriptor.requires(), args,
                new AuthzContext(session.id(), turnId, exec.traceId(),
                        Map.of("toolId", toolDescriptor.id().value())));

        if (decision instanceof AuthzDecision.Deny deny) {
            return denialFlow(toolDescriptor, args, deny, personality, session, exec,
                    recorder, turnId, message, callIdHint, llmDriven);
        }

        // B2.8 — confirmable tools pause for explicit confirm before dispatch.
        if (!skipConfirm && toolDescriptor.policy() != null && toolDescriptor.policy().confirmable()) {
            return confirmFlow(toolDescriptor, args, personality, session, exec, recorder, turnId, message);
        }

        ToolExecutor.Outcome outcome = toolExecutor.execute(toolDescriptor, args, exec, callIdHint);
        return switch (outcome) {
            case ToolExecutor.Outcome.NeedsInput ni ->
                    elicitationFlow(toolDescriptor, args, ni, personality, session, exec, recorder, turnId, message);
            case ToolExecutor.Outcome.Dispatched(var result) ->
                    dispatchedFlow(toolDescriptor, args, result, profile, session, exec, recorder, turnId, message);
        };
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
                                           UserMessage message) {
        Turn.ToolCall call = new Turn.ToolCall(result.callId(), toolDescriptor.id(), args);

        String spoken = switch (result.status()) {
            case OK -> "You said: \""
                    + String.valueOf(args.getOrDefault("text", "")) + "\". Anything else?";
            case DENIED -> "I can't echo that.";
            case FAILED -> "Sorry — that didn't work (" + result.error() + ").";
        };

        // B2.7 — after a clean tool dispatch, surface the capability's
        // follow-up hints as a quick-reply UiHint so the UI can render chips.
        List<String> followUps = (result.status() == Turn.ToolResult.Status.OK)
                ? followUpHintsForTool(profile, toolDescriptor.id())
                : List.of();

        List<TurnEvent> events = new java.util.ArrayList<>(4);
        events.add(new TurnEvent.ToolCall(call.callId(), toolDescriptor.id(), args));
        events.add(new TurnEvent.ToolResult(call.callId(), result.status(), result.value(), result.error()));
        events.add(new TurnEvent.Token(spoken));
        if (!followUps.isEmpty()) {
            events.add(new TurnEvent.UiHint("quick_replies", Map.of("items", followUps)));
        }

        return Flux.fromIterable(events)
                .doOnComplete(() -> recordTurns(recorder, session, exec, turnId, message, spoken,
                        List.of(call), List.of(result)));
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
    private Flux<TurnEvent> denialFlow(ToolDescriptor toolDescriptor,
                                       Map<String, Object> args,
                                       AuthzDecision.Deny deny,
                                       PersonalityFragment personality,
                                       Session session,
                                       ExecutionContext exec,
                                       ConversationRecorder recorder,
                                       Ids.TurnId turnId,
                                       UserMessage message,
                                       String callIdHint,
                                       boolean llmDriven) {
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
