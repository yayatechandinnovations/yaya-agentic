package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.auth.*;
import com.yayatechandinnovations.yayaagentic.core.*;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.HelloWorldProfileBootstrap;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
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

    private final Map<Ids.SessionId, Session> active = new ConcurrentHashMap<>();

    public DefaultConversationEngine(PersonalityProvider personalityProvider,
                                     ProfileResolverChain resolverChain,
                                     ProfileRegistry profileRegistry,
                                     Authenticator authenticator,
                                     Authorizer authorizer,
                                     RecorderRouter recorderRouter,
                                     LlmClient llmClient,
                                     ToolExecutor toolExecutor,
                                     M0Catalog catalog) {
        this.personalityProvider = personalityProvider;
        this.resolverChain = resolverChain;
        this.profileRegistry = profileRegistry;
        this.authenticator = authenticator;
        this.authorizer = authorizer;
        this.recorderRouter = recorderRouter;
        this.llmClient = llmClient;
        this.toolExecutor = toolExecutor;
        this.catalog = catalog;
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

        Optional<EchoIntent> echo = detectEcho(message.text());

        Flux<TurnEvent> body = echo
                .map(intent -> dispatchAndStream(intent, profile, personality, session, exec, recorder, turnId, message))
                .orElseGet(() -> streamLlmReply(personality, profile, session, message, recorder, exec, turnId));

        return body.concatWith(Mono.fromCallable(() -> TurnEvent.End.of(turnId, 0, 0)));
    }

    @Override
    public void end(Ids.SessionId sessionId, AuthContext auth) {
        Session session = active.remove(sessionId);
        if (session == null) return;
        ConversationRecorder recorder = recorderRouter.recorderFor(session,
                new RecorderRouter.RecordingDecisionContext(Map.of()));
        recorder.onSessionEnded(sessionId, new Operations.SessionEndContext(Instant.now(), "client-ended"));
    }

    // ---- Internals --------------------------------------------------

    private Flux<TurnEvent> streamLlmReply(PersonalityFragment personality,
                                           Profile profile,
                                           Session session,
                                           UserMessage message,
                                           ConversationRecorder recorder,
                                           ExecutionContext exec,
                                           Ids.TurnId turnId) {
        StringBuilder accumulated = new StringBuilder();
        String systemPrompt = renderSystemPrompt(personality, profile);

        return llmClient.stream(new LlmClient.LlmRequest(systemPrompt, List.of(), message.text()))
                .map(chunk -> {
                    accumulated.append(chunk.text());
                    return (TurnEvent) new TurnEvent.Token(chunk.text());
                })
                .doOnComplete(() -> recordTurns(recorder, session, exec, turnId, message, accumulated.toString(),
                        List.of(), List.of()));
    }

    private Flux<TurnEvent> dispatchAndStream(EchoIntent intent,
                                              Profile profile,
                                              PersonalityFragment personality,
                                              Session session,
                                              ExecutionContext exec,
                                              ConversationRecorder recorder,
                                              Ids.TurnId turnId,
                                              UserMessage message) {
        ToolDescriptor toolDescriptor = catalog.tool(HelloWorldProfileBootstrap.ECHO)
                .orElseThrow(() -> new IllegalStateException("echo tool descriptor missing"));

        AuthzDecision decision = authorizer.authorize(
                session.principal(), toolDescriptor.requires(), Map.of("text", intent.text()),
                new AuthzContext(session.id(), turnId, exec.traceId(),
                        Map.of("toolId", toolDescriptor.id().value())));

        if (decision instanceof AuthzDecision.Deny deny) {
            String reply = "I can't do that: " + deny.userSafeReason();
            return Flux.<TurnEvent>just(new TurnEvent.Token(reply))
                    .doOnComplete(() -> recordTurns(recorder, session, exec, turnId, message, reply, List.of(), List.of()));
        }

        Map<String, Object> args = Map.of("text", intent.text());
        Turn.ToolCall call = new Turn.ToolCall(UUID.randomUUID().toString(), toolDescriptor.id(), args);
        Turn.ToolResult result = toolExecutor.execute(toolDescriptor, args, exec);

        String spoken = switch (result.status()) {
            case OK -> "You said: \"" + intent.text() + "\". Anything else?";
            case DENIED -> "I can't echo that.";
            case FAILED -> "Sorry — the echo tool failed (" + result.error() + ").";
        };

        return Flux.<TurnEvent>just(
                        new TurnEvent.ToolCall(call.callId(), toolDescriptor.id(), args),
                        new TurnEvent.ToolResult(call.callId(), result.status(), result.value(), result.error()),
                        new TurnEvent.Token(spoken)
                )
                .doOnComplete(() -> recordTurns(recorder, session, exec, turnId, message, spoken,
                        List.of(call), List.of(result)));
    }

    private String renderSystemPrompt(PersonalityFragment personality, Profile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append(personality.voiceAndTone()).append("\n\n");
        sb.append("Rules:\n");
        for (var rule : personality.rules()) {
            sb.append("- ").append(rule.text()).append("\n");
        }
        sb.append("\nRole:\n").append(profile.systemPromptFragment());
        return sb.toString();
    }

    private void recordTurns(ConversationRecorder recorder, Session session, ExecutionContext exec,
                             Ids.TurnId turnId, UserMessage userMessage, String assistantText,
                             List<Turn.ToolCall> calls, List<Turn.ToolResult> results) {
        Instant now = Instant.now();
        RecorderContext rc = ctx(session.tenant(), session.principal());
        recorder.onTurnRecorded(session.id(),
                new Turn(new Ids.TurnId(UUID.randomUUID().toString()), session.id(), 0,
                        Turn.Role.USER, userMessage.text(), List.of(), List.of(), List.of(),
                        null, Map.of(), now),
                rc);
        recorder.onTurnRecorded(session.id(),
                new Turn(turnId, session.id(), 1,
                        Turn.Role.ASSISTANT, assistantText, calls, results, List.of(),
                        new Turn.ModelInfo("stub-or-anthropic", "m0", 0, 0), Map.of(), now),
                rc);
    }

    private RecorderContext ctx(Ids.TenantId tenant, Principal principal) {
        return new RecorderContext(tenant, principal, UUID.randomUUID().toString(), Map.of());
    }

    private Optional<EchoIntent> detectEcho(String text) {
        if (text == null) return Optional.empty();
        String t = text.trim();
        String lower = t.toLowerCase();
        if (lower.startsWith("/echo ")) return Optional.of(new EchoIntent(t.substring(6).trim()));
        if (lower.startsWith("echo ")) return Optional.of(new EchoIntent(t.substring(5).trim()));
        return Optional.empty();
    }

    private record EchoIntent(String text) {}
}
