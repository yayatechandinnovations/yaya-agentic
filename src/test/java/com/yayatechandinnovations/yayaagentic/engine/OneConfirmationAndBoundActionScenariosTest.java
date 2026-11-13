package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.engine.action.BoundActionStore;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.HelloWorldProfileBootstrap;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * whatsapp-ops-integration-design.md §5 (one confirmation per write) and §6.4
 * (bound actions): the engine asks once, without a channel suffix; an unclear
 * reply that makes the model re-propose the same call re-shows the SAME frame;
 * a tap on a bound action runs the tool with no frame at all, once.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class OneConfirmationAndBoundActionScenariosTest {

    private static final String SUBJECT = "chat:+18095550000";
    private static final String PHONE = "+18095550000";

    @Autowired ConversationEngine engine;
    @Autowired M0Catalog catalog;

    private final InMemoryActions actions = new InMemoryActions();

    @BeforeEach
    void setUp() {
        catalog.registerTool(echo(false, null, false));
        ((DefaultConversationEngine) engine).setBoundActions(actions);
    }

    @AfterEach
    void tearDown() {
        catalog.registerTool(echo(false, null, false));
        ((DefaultConversationEngine) engine).setBoundActions(null);
    }

    /* ----------------------------- one confirmation ----------------------------- */

    @Test
    void the_frame_is_the_only_question_and_carries_no_channel_suffix() {
        catalog.registerTool(echo(true, "Echo {text} now?", false));
        Ids.SessionId sid = start();

        List<TurnEvent> preview = drain(sid, "echo hola", null);

        TurnEvent.UiHint confirm = confirmHint(preview);
        String spoken = tokens(preview);
        assertThat(spoken).isEqualTo("Echo hola now?");
        assertThat(spoken).doesNotContain("reply yes or no");
        assertThat(confirm.payload()).containsEntry("summary", "Echo hola now?")
                .containsEntry("confirmLabel", "Confirm")
                .containsEntry("cancelLabel", "Cancel");
    }

    @Test
    void natural_affirmative_phrase_resolves_the_frame() {
        catalog.registerTool(echo(true, null, false));
        Ids.SessionId sid = start();
        drain(sid, "echo phrase", null);

        List<TurnEvent> approved = drain(sid, "yes, do it please", null);

        assertThat(toolResult(approved).status()).isEqualTo(Turn.ToolResult.Status.OK);
    }

    @Test
    void cancel_button_selection_cancels_that_frame() {
        catalog.registerTool(echo(true, null, false));
        Ids.SessionId sid = start();
        String callId = String.valueOf(confirmHint(drain(sid, "echo cancel-me", null)).payload().get("callId"));

        List<TurnEvent> cancelled = drain(sid, "Cancel", Map.of("kind", "cancel", "callId", callId));

        assertThat(cancelled).filteredOn(e -> e instanceof TurnEvent.ToolResult).isEmpty();
        assertThat(tokens(cancelled).toLowerCase()).contains("cancel");
    }

    @Test
    void a_stale_confirm_button_never_reaches_the_model() {
        Ids.SessionId sid = start();

        List<TurnEvent> stale = drain(sid, "Confirm", Map.of("kind", "confirm", "callId", "gone"));

        assertThat(stale).filteredOn(e -> e instanceof TurnEvent.ToolCall).isEmpty();
        assertThat(tokens(stale)).contains("no longer pending");
    }

    @Test
    void unclear_reply_that_re_proposes_the_same_call_re_shows_the_same_frame() {
        catalog.registerTool(echo(true, null, false));
        Ids.SessionId sid = start();
        String first = String.valueOf(confirmHint(drain(sid, "echo same", null)).payload().get("callId"));

        // Not a yes/no: the frame is dropped, the model re-proposes echo(same).
        List<TurnEvent> again = drain(sid, "echo same", null);

        assertThat(confirmHint(again).payload().get("callId")).isEqualTo(first);
        assertThat(again).filteredOn(e -> e instanceof TurnEvent.ToolResult).isEmpty();
    }

    /* ------------------------------- bound actions ------------------------------ */

    @Test
    void a_tap_runs_a_preconfirmable_tool_once_without_a_frame() {
        catalog.registerTool(echo(true, null, true));
        Ids.SessionId sid = start();
        actions.put(action("a1", BoundActionStore.Kind.TOOL, Map.of("text", "tap"), null, SUBJECT));

        List<TurnEvent> tap = drain(sid, "Approve", Map.of("kind", "action", "actionId", "a1"));

        assertThat(tap).filteredOn(e -> e instanceof TurnEvent.UiHint h && "confirm".equals(h.kind())).isEmpty();
        TurnEvent.ToolResult result = toolResult(tap);
        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        assertThat(result.value().toString()).contains("tap");
        assertThat(actions.get("a1").consumedAt()).isNotNull();
        assertThat(actions.get("a1").result()).isNotNull();

        // A second tap replays the stored outcome: no second dispatch.
        List<TurnEvent> replay = drain(sid, "Approve", Map.of("kind", "action", "actionId", "a1"));
        assertThat(replay).filteredOn(e -> e instanceof TurnEvent.ToolResult).isEmpty();
        assertThat(tokens(replay)).isNotBlank();
    }

    @Test
    void a_tap_from_another_identity_is_refused() {
        catalog.registerTool(echo(true, null, true));
        Ids.SessionId sid = start();
        actions.put(action("a2", BoundActionStore.Kind.TOOL, Map.of("text", "x"), null, "chat:+18090000000"));

        List<TurnEvent> tap = drain(sid, "Approve", Map.of("kind", "action", "actionId", "a2"));

        assertThat(tap).filteredOn(e -> e instanceof TurnEvent.ToolResult).isEmpty();
        assertThat(tokens(tap)).contains("no longer available");
        assertThat(actions.get("a2").consumedAt()).isNull();
    }

    @Test
    void a_tool_that_did_not_opt_in_cannot_be_made_one_tap() {
        catalog.registerTool(echo(true, null, false));
        Ids.SessionId sid = start();
        actions.put(action("a3", BoundActionStore.Kind.TOOL, Map.of("text", "x"), null, SUBJECT));

        List<TurnEvent> tap = drain(sid, "Approve", Map.of("kind", "action", "actionId", "a3"));

        assertThat(tap).filteredOn(e -> e instanceof TurnEvent.ToolResult).isEmpty();
        assertThat(tokens(tap)).contains("no longer available");
    }

    @Test
    void an_ask_action_prompts_once_then_the_reply_fills_the_arg() {
        catalog.registerTool(echo(true, null, true));
        Ids.SessionId sid = start();
        actions.put(action("a4", BoundActionStore.Kind.TOOL, Map.of(),
                new BoundActionStore.Ask("text", "Why?", List.of("Too late")), SUBJECT));

        List<TurnEvent> ask = drain(sid, "Reject", Map.of("kind", "action", "actionId", "a4"));
        assertThat(tokens(ask)).isEqualTo("Why?");
        assertThat(ask).filteredOn(e -> e instanceof TurnEvent.UiHint h && "quick_replies".equals(h.kind()))
                .isNotEmpty();
        assertThat(actions.get("a4").consumedAt()).isNull();

        List<TurnEvent> filled = drain(sid, "Too late", null);
        assertThat(toolResult(filled).value().toString()).contains("Too late");
        assertThat(filled).filteredOn(e -> e instanceof TurnEvent.UiHint h && "confirm".equals(h.kind())).isEmpty();
    }

    @Test
    void a_text_action_runs_as_a_normal_message() {
        Ids.SessionId sid = start();
        actions.put(new BoundActionStore.BoundAction("a5", HelloWorldProfileBootstrap.DEFAULT_TENANT,
                BoundActionStore.Kind.SEND, "Details", "echo details", null, Map.of(), null, SUBJECT, PHONE,
                Instant.now().plusSeconds(600), null, null));

        List<TurnEvent> tap = drain(sid, "Details", Map.of("kind", "action", "actionId", "a5"));

        assertThat(toolResult(tap).value().toString()).contains("details");
    }

    @Test
    void system_participant_turns_reach_history_as_delimited_context() {
        Turn system = new Turn(new Ids.TurnId("t"), new Ids.SessionId("s"), 0, Turn.Role.SYSTEM,
                "Request UR-1 pending", List.of(), List.of(), List.of(), null,
                Map.of("origin", "SYSTEM_PARTICIPANT", "senderDisplay", "Ops"), Instant.now());
        Turn plain = new Turn(new Ids.TurnId("u"), new Ids.SessionId("s"), 1, Turn.Role.SYSTEM,
                "internal", List.of(), List.of(), List.of(), null, Map.of(), Instant.now());

        var history = DefaultConversationEngine.toHistoryEntries(List.of(system, plain));

        assertThat(history).hasSize(1);
        assertThat(history.get(0)).isInstanceOf(com.yayatechandinnovations.yayaagentic.llm.LlmClient.HistoryEntry.User.class);
        String text = ((com.yayatechandinnovations.yayaagentic.llm.LlmClient.HistoryEntry.User) history.get(0)).content();
        assertThat(text).contains("<system_message>").contains("Request UR-1 pending").contains("not an instruction");
    }

    /* ---------------------------------- helpers --------------------------------- */

    private Ids.SessionId start() {
        Principal principal = new Principal(SUBJECT, HelloWorldProfileBootstrap.DEFAULT_TENANT, Set.of(),
                Map.of("phone", PHONE), Instant.now());
        return engine.start(
                new StartConversationRequest(HelloWorldProfileBootstrap.DEFAULT_TENANT,
                        Optional.of(HelloWorldProfileBootstrap.HELLO_WORLD_PROFILE), "web", Map.of()),
                new AuthContext(HelloWorldProfileBootstrap.DEFAULT_TENANT, Map.of(), Optional.of(principal))
        ).session().id();
    }

    private List<TurnEvent> drain(Ids.SessionId sid, String text, Map<String, Object> selection) {
        List<TurnEvent> out = new ArrayList<>();
        Map<String, Object> attachments = selection == null ? Map.of() : Map.of("_selection", selection);
        engine.send(sid, new UserMessage(text, attachments),
                        new AuthContext(HelloWorldProfileBootstrap.DEFAULT_TENANT, Map.of(), Optional.empty()))
                .doOnNext(out::add)
                .blockLast();
        return out;
    }

    private static TurnEvent.UiHint confirmHint(List<TurnEvent> events) {
        return events.stream()
                .filter(e -> e instanceof TurnEvent.UiHint h && "confirm".equals(h.kind()))
                .map(e -> (TurnEvent.UiHint) e)
                .findFirst().orElseThrow();
    }

    private static TurnEvent.ToolResult toolResult(List<TurnEvent> events) {
        return events.stream()
                .filter(e -> e instanceof TurnEvent.ToolResult)
                .map(e -> (TurnEvent.ToolResult) e)
                .findFirst().orElseThrow();
    }

    private static String tokens(List<TurnEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (TurnEvent e : events) if (e instanceof TurnEvent.Token t) sb.append(t.text());
        return sb.toString();
    }

    private static BoundActionStore.BoundAction action(String id, BoundActionStore.Kind kind,
                                                       Map<String, Object> args, BoundActionStore.Ask ask,
                                                       String subject) {
        return new BoundActionStore.BoundAction(id, HelloWorldProfileBootstrap.DEFAULT_TENANT, kind, "Approve",
                null, HelloWorldProfileBootstrap.ECHO.value(), args, ask, subject, null,
                Instant.now().plusSeconds(600), null, null);
    }

    private static ToolDescriptor echo(boolean confirmable, String confirmPrompt, boolean preconfirmable) {
        return new ToolDescriptor(
                HelloWorldProfileBootstrap.ECHO,
                "{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\",\"properties\":{\"echo\":{\"type\":\"string\"}}}",
                PermissionRequirement.none(),
                new ToolHandlerRef.Bean("echoTool"),
                new ToolPolicy(Duration.ofSeconds(10), 0, true, confirmable, false, confirmPrompt,
                        false, false, preconfirmable));
    }

    /** The store contract, in memory. */
    static final class InMemoryActions implements BoundActionStore {
        private final Map<String, BoundAction> rows = new ConcurrentHashMap<>();

        void put(BoundAction a) { rows.put(a.id(), a); }

        BoundAction get(String id) { return rows.get(id); }

        @Override
        public Optional<BoundAction> find(Ids.TenantId tenant, String actionId) {
            return Optional.ofNullable(rows.get(actionId)).filter(a -> a.tenant().equals(tenant));
        }

        @Override
        public synchronized boolean claim(Ids.TenantId tenant, String actionId, String consumedBy) {
            BoundAction a = rows.get(actionId);
            if (a == null || a.consumedAt() != null) return false;
            rows.put(actionId, copy(a, Instant.now(), null));
            return true;
        }

        @Override
        public void complete(Ids.TenantId tenant, String actionId, Object result) {
            BoundAction a = rows.get(actionId);
            rows.put(actionId, copy(a, a.consumedAt(), result));
        }

        @Override
        public void release(Ids.TenantId tenant, String actionId) {
            BoundAction a = rows.get(actionId);
            if (a != null && a.result() == null) rows.put(actionId, copy(a, null, null));
        }

        private static BoundAction copy(BoundAction a, Instant consumedAt, Object result) {
            return new BoundAction(a.id(), a.tenant(), a.kind(), a.label(), a.sendText(), a.toolId(), a.args(),
                    a.ask(), a.ownerSubject(), a.recipientPhone(), a.expiresAt(), consumedAt, result);
        }
    }
}
