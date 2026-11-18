package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.HelloWorldProfileBootstrap;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import com.yayatechandinnovations.yayaagentic.llm.StubLlmClient;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.profile.Capability;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.testsupport.ScriptedLlmClient;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Assistant-channels design §2 at the engine seam: every producer's
 * {@code quick_replies} leaves in the one shape; a tool's {@code _askUser}
 * is stripped and offered after the final text; a tapped option is the
 * user's text; and the reply suggester fills in when the model asks a
 * question nobody gave answers for — never over a frame.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, ReplyChipsScenariosTest.Config.class})
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "spring.main.allow-bean-definition-overriding=true"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReplyChipsScenariosTest {

    private static final Ids.ToolId ASKER = new Ids.ToolId("chips_asker");
    private static final Ids.ToolId WRITER = new Ids.ToolId("chips_writer");

    @Autowired ConversationEngine engine;
    @Autowired M0Catalog catalog;
    @Autowired ProfileRegistry profiles;
    @Autowired ScriptedLlmClient llm;
    @Autowired StubLlmClient stub;
    @Autowired AskerTool asker;
    @MockitoSpyBean WorkingMemory workingMemory;

    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final Ids.CapabilityId capability = new Ids.CapabilityId("chips-cap-" + run);
    private final Ids.ProfileId suggesting = new Ids.ProfileId("chips-suggesting-" + run, 1);
    private final Ids.ProfileId plain = new Ids.ProfileId("chips-plain-" + run, 1);

    @BeforeAll
    void install() {
        String anyObject = "{\"type\":\"object\"}";
        catalog.registerTool(new ToolDescriptor(ASKER, anyObject, anyObject, PermissionRequirement.none(),
                new ToolHandlerRef.Bean("chipsAskerTool"), ToolPolicy.defaults()));
        catalog.registerTool(new ToolDescriptor(WRITER,
                "{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"}}}",
                anyObject, PermissionRequirement.none(), new ToolHandlerRef.Bean("echoTool"),
                new ToolPolicy(Duration.ofSeconds(10), 0, false, true, false, "¿Aplicar «{text}»?")));
        catalog.registerCapability(new Capability(capability, "Chips", "Chips tools",
                "Use the chips tools.", List.of(ASKER, WRITER), List.of(), PermissionRequirement.none()));
        profiles.register(profile(suggesting, Map.of("policy", Map.of("replySuggestions", true))));
        profiles.register(profile(plain, Map.of()));
    }

    private Profile profile(Ids.ProfileId id, Map<String, Object> metadata) {
        return new Profile(id, HelloWorldProfileBootstrap.DEFAULT_TENANT, "Chips " + id.value(),
                "Hola.", "You are the chips assistant.", List.of(capability), List.of(), null, "es",
                metadata, Map.of(), null, null);
    }

    @BeforeEach
    void reset() {
        llm.reset();
        stub.setStructuredResponder(null);
        asker.answer.set(Map.of("ok", true));
    }

    @AfterEach
    void clear() {
        stub.setStructuredResponder(null);
        org.mockito.Mockito.reset(workingMemory);
    }

    /* --------------------------- the turn's end is cheap ---------------------- */

    @Test
    void a_plain_text_turn_reads_no_working_memory_when_it_closes() {
        List<StackTraceElement[]> reads = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            reads.add(new Throwable().getStackTrace());
            return inv.callRealMethod();
        }).when(workingMemory).get(any());
        Ids.SessionId sid = start(plain);

        llm.say("Hola, ¿en qué te ayudo?");
        reads.clear();
        List<TurnEvent> turn = drain(sid, "hola", null);
        assertThat(turn.get(turn.size() - 1)).isInstanceOf(TurnEvent.End.class);
        assertThat(reads).noneMatch(ReplyChipsScenariosTest::fromClosingChips);

        // Positive control: after a tool ran, the ask slot is read there.
        asker.answer.set(Map.of("ok", true, "_askUser", Map.of("kind", "yes_no", "question", "¿Lo mando?")));
        llm.tool("c1", ASKER.value(), Map.of()).say("¿Te lo mando?");
        reads.clear();
        turn = drain(sid, "mándamelo", null);
        assertThat(chips(turn)).hasSize(1);
        assertThat(reads).anyMatch(ReplyChipsScenariosTest::fromClosingChips);
    }

    private static boolean fromClosingChips(StackTraceElement[] stack) {
        return Arrays.stream(stack).anyMatch(f -> f.getMethodName().equals("closingChips"));
    }

    /* ------------------------------- _askUser -------------------------------- */

    @Test
    void a_tools_ask_is_stripped_and_offered_after_the_final_text() {
        asker.answer.set(Map.of("ok", true, "_askUser", Map.of("kind", "choice", "question", "¿Para todas?",
                "options", List.of(Map.of("id", "all", "label", "Todas", "send", "Para todas las estaciones"),
                        Map.of("id", "one", "label", "Solo la 07")))));
        llm.tool("c1", ASKER.value(), Map.of()).say("¿Para todas las estaciones o solo la 07?");
        Ids.SessionId sid = start(plain);

        List<TurnEvent> turn = drain(sid, "pon un límite", null);

        TurnEvent.ToolResult result = toolResult(turn);
        assertThat(result.value()).isEqualTo(Map.of("ok", true));
        assertThat(llm.lastRequest().history().toString()).doesNotContain("_askUser");
        List<String> kinds = turn.stream().map(e -> e.getClass().getSimpleName()).toList();
        assertThat(kinds).containsExactly("ToolCall", "ToolResult", "Token", "UiHint", "End");
        TurnEvent.UiHint chips = chips(turn).get(0);
        assertThat(chips.payload().get("source")).isEqualTo("ask");
        assertThat(QuickReplies.read(chips.payload())).extracting(QuickReplies.Option::id).containsExactly("all", "one");
        assertThat(QuickReplies.read(chips.payload()).get(0).send()).isEqualTo("Para todas las estaciones");
    }

    @Test
    void a_yes_no_ask_is_the_localised_pair_and_the_last_ask_wins() {
        asker.answer.set(Map.of("ok", true, "_askUser", Map.of("kind", "yes_no", "question", "¿Lo mando?")));
        llm.tool("c1", ASKER.value(), Map.of()).tool("c2", ASKER.value(), Map.of()).say("¿Te lo mando en PDF?");
        Ids.SessionId sid = start(plain);

        List<TurnEvent> turn = drain(sid, "mándamelo", null);

        List<TurnEvent.UiHint> chips = chips(turn);
        assertThat(chips).hasSize(1);
        assertThat(QuickReplies.read(chips.get(0).payload())).extracting(QuickReplies.Option::label)
                .containsExactly("Sí", "No");
        assertThat(turn.get(turn.size() - 1)).isInstanceOf(TurnEvent.End.class);
        assertThat(turn.get(turn.size() - 2)).isSameAs(chips.get(0));
    }

    @Test
    void a_tools_ask_takes_the_place_of_the_rule_chips_of_the_same_turn() {
        // Verification 5H D1: a vision-identified ticket answered with write chips
        // («Anular») AND an _askUser («Sí, es el N») must offer only the ask.
        asker.answer.set(Map.of("ok", true,
                "_uiHint", Map.of("kind", "quick_replies", "payload", Map.of("source", "rule", "options",
                        List.of(Map.of("id", "void", "label", "Anular", "style", "danger"),
                                Map.of("id", "open", "label", "Ver en la app")))),
                "_askUser", Map.of("kind", "yes_no", "question", "¿Es el ticket 1234567890?",
                        "options", List.of(Map.of("id", "yes", "label", "Sí, es el …890", "send", "Sí, es el ticket 1234567890"),
                                Map.of("id", "no", "label", "No, es otro")))));
        llm.tool("c1", ASKER.value(), Map.of()).say("Veo el ticket …890. ¿Es ese?");
        Ids.SessionId sid = start(plain);

        List<TurnEvent> turn = drain(sid, "mira esta foto", null);

        List<TurnEvent.UiHint> chips = chips(turn);
        assertThat(chips).hasSize(1);
        assertThat(chips.get(0).payload().get("source")).isEqualTo("ask");
        assertThat(QuickReplies.read(chips.get(0).payload())).extracting(QuickReplies.Option::id).containsExactly("yes", "no");
        assertThat(turn.get(turn.size() - 2)).isSameAs(chips.get(0));
        assertThat(turn.get(turn.size() - 1)).isInstanceOf(TurnEvent.End.class);
    }

    @Test
    void rule_chips_without_an_ask_leave_after_the_final_text() {
        asker.answer.set(Map.of("ok", true,
                "_uiHint", Map.of("kind", "quick_replies", "payload", Map.of("source", "rule", "options",
                        List.of(Map.of("id", "void", "label", "Anular"), Map.of("id", "open", "label", "Ver en la app"))))));
        llm.tool("c1", ASKER.value(), Map.of()).say("Ticket confirmado. ¿Qué quieres hacer?");
        stub.setStructuredResponder(r -> { throw new AssertionError("the suggester must not run over rule chips"); });
        Ids.SessionId sid = start(suggesting);

        List<TurnEvent> turn = drain(sid, "busca el ticket", null);

        List<String> kinds = turn.stream().map(e -> e.getClass().getSimpleName()).toList();
        assertThat(kinds).containsExactly("ToolCall", "ToolResult", "Token", "UiHint", "End");
        List<TurnEvent.UiHint> chips = chips(turn);
        assertThat(chips).hasSize(1);
        assertThat(chips.get(0).payload().get("source")).isEqualTo("rule");
        assertThat(QuickReplies.read(chips.get(0).payload())).extracting(QuickReplies.Option::label)
                .containsExactly("Anular", "Ver en la app");
    }

    @Test
    void a_frame_in_the_same_turn_suppresses_the_ask() {
        asker.answer.set(Map.of("ok", true, "_askUser", Map.of("kind", "yes_no")));
        llm.round(Flux.just(new LlmClient.LlmEvent.ToolUseProposal("c1", ASKER.value(), Map.of()),
                new LlmClient.LlmEvent.ToolUseProposal("c2", WRITER.value(), Map.of("text", "hola")),
                new LlmClient.LlmEvent.Done(LlmClient.StopReason.TOOL_USE, ScriptedLlmClient.ROUND_USAGE)));
        Ids.SessionId sid = start(plain);

        List<TurnEvent> turn = drain(sid, "escribe hola", null);

        assertThat(turn).anyMatch(e -> e instanceof TurnEvent.UiHint h && "confirm".equals(h.kind()));
        assertThat(chips(turn)).isEmpty();
        // The ask did not outlive its turn.
        llm.say("Listo.");
        assertThat(chips(drain(sid, "cancela", Map.of("kind", "cancel", "callId", confirmCallId(turn))))).isEmpty();
    }

    /* -------------------------- one shape, every producer -------------------- */

    @Test
    void follow_up_hints_and_rule_chips_leave_in_the_canonical_shape() {
        Ids.SessionId sid = start(HelloWorldProfileBootstrap.HELLO_WORLD_PROFILE);
        llm.tool("c1", HelloWorldProfileBootstrap.ECHO.value(), Map.of("text", "hola")).say("Dicho.");
        List<TurnEvent> turn = drain(sid, "echo hola", null);
        List<TurnEvent.UiHint> chips = chips(turn);
        assertThat(chips).hasSize(1);
        assertThat(chips.get(0).payload()).containsKey("options").containsEntry("source", "start")
                .doesNotContainKey("items");
        assertThat(QuickReplies.read(chips.get(0).payload())).extracting(QuickReplies.Option::label)
                .containsExactly("echo something else", "what else can you do?");
    }

    /* ----------------------------- option selection -------------------------- */

    @Test
    void a_tapped_option_sends_its_text_and_keeps_the_tap_on_record() {
        Ids.SessionId sid = start(plain);
        llm.say("Vale.");
        drain(sid, "Anular", Map.of("kind", "option", "id", "undo", "send", "Anular el ticket 4411"));
        LlmClient.HistoryEntry.User user = lastUser(llm.lastRequest());
        assertThat(user.content()).isEqualTo("Anular el ticket 4411");

        llm.say("Vale.");
        drain(sid, "Solo la 07", Map.of("kind", "option", "id", "one"));
        assertThat(lastUser(llm.lastRequest()).content()).isEqualTo("Solo la 07");

        llm.say("Vale.");
        drain(sid, "", Map.of("kind", "option", "id", "one"));
        assertThat(lastUser(llm.lastRequest()).content()).isEqualTo("one");
    }

    /* ------------------------------- suggester ------------------------------- */

    @Test
    void a_question_without_chips_gets_them_from_the_suggester_before_the_end() {
        llm.say("¿Quieres que te lo mande en PDF?");
        Ids.SessionId sid = start(suggesting);

        List<TurnEvent> turn = drain(sid, "dame el cuadre", null);

        List<TurnEvent.UiHint> chips = chips(turn);
        assertThat(chips).hasSize(1);
        assertThat(chips.get(0).payload().get("source")).isEqualTo("suggester");
        assertThat(QuickReplies.read(chips.get(0).payload())).extracting(QuickReplies.Option::label)
                .containsExactly("Sí, hazlo", "Mejor no");
        assertThat(turn.get(turn.size() - 2)).isSameAs(chips.get(0));
        assertThat(turn.get(turn.size() - 1)).isInstanceOf(TurnEvent.End.class);
    }

    @Test
    void the_suggester_is_given_the_last_user_text_and_the_final_answer_only() {
        AtomicReference<String> input = new AtomicReference<>();
        stub.setStructuredResponder(r -> {
            input.set(r.input());
            return Map.of("kind", "choice", "options", List.of("En la 12", "En todo el grupo"));
        });
        llm.say("¿Lo detengo en la 12 o en todo el grupo?");
        Ids.SessionId sid = start(suggesting);

        List<TurnEvent> turn = drain(sid, "detén la venta", null);

        assertThat(input.get()).contains("User: detén la venta").contains("Assistant: ¿Lo detengo en la 12 o en todo el grupo?");
        assertThat(QuickReplies.read(chips(turn).get(0).payload())).extracting(QuickReplies.Option::label)
                .containsExactly("En la 12", "En todo el grupo");
    }

    @Test
    void the_suggester_stays_quiet_without_a_question_a_policy_or_when_chips_or_a_frame_exist() {
        // No question.
        llm.say("Listo, cuadre enviado.");
        assertThat(chips(drain(start(suggesting), "dame el cuadre", null))).isEmpty();
        // No policy on the profile.
        llm.say("¿Quieres que te lo mande en PDF?");
        assertThat(chips(drain(start(plain), "dame el cuadre", null))).isEmpty();
        // Chips already there (the ask).
        asker.answer.set(Map.of("ok", true, "_askUser", Map.of("kind", "yes_no")));
        llm.tool("c1", ASKER.value(), Map.of()).say("¿Lo mando?");
        List<TurnEvent.UiHint> asked = chips(drain(start(suggesting), "mándamelo", null));
        assertThat(asked).hasSize(1);
        assertThat(asked.get(0).payload().get("source")).isEqualTo("ask");
        // A confirm frame.
        llm.tool("c2", WRITER.value(), Map.of("text", "hola"));
        List<TurnEvent> framed = drain(start(suggesting), "escribe hola?", null);
        assertThat(framed).anyMatch(e -> e instanceof TurnEvent.UiHint h && "confirm".equals(h.kind()));
        assertThat(chips(framed)).isEmpty();
        // The model answered none.
        stub.setStructuredResponder(r -> Map.of("kind", "none", "options", List.of()));
        llm.say("¿Cuál es el número del ticket?");
        assertThat(chips(drain(start(suggesting), "anula", null))).isEmpty();
    }

    @Test
    void a_failing_suggester_costs_the_turn_nothing() {
        stub.setStructuredResponder(r -> { throw new IllegalStateException("provider down"); });
        llm.say("¿Seguimos?");
        List<TurnEvent> turn = drain(start(suggesting), "hola", null);
        assertThat(chips(turn)).isEmpty();
        assertThat(turn.get(turn.size() - 1)).isInstanceOf(TurnEvent.End.class);
    }

    /* -------------------------------- helpers -------------------------------- */

    private Ids.SessionId start(Ids.ProfileId profile) {
        return engine.start(new StartConversationRequest(HelloWorldProfileBootstrap.DEFAULT_TENANT,
                        Optional.of(profile), "web", Map.of()),
                new AuthContext(HelloWorldProfileBootstrap.DEFAULT_TENANT, Map.of(), Optional.empty())).session().id();
    }

    private List<TurnEvent> drain(Ids.SessionId sid, String text, Map<String, Object> selection) {
        List<TurnEvent> out = new ArrayList<>();
        Map<String, Object> attachments = selection == null ? Map.of() : Map.of("_selection", selection);
        engine.send(sid, new UserMessage(text, attachments),
                        new AuthContext(HelloWorldProfileBootstrap.DEFAULT_TENANT, Map.of(), Optional.empty()))
                .doOnNext(out::add)
                .blockLast(Duration.ofSeconds(30));
        return out;
    }

    private static List<TurnEvent.UiHint> chips(List<TurnEvent> events) {
        return events.stream()
                .filter(e -> e instanceof TurnEvent.UiHint h && QuickReplies.KIND.equals(h.kind()))
                .map(e -> (TurnEvent.UiHint) e).toList();
    }

    private static TurnEvent.ToolResult toolResult(List<TurnEvent> events) {
        return events.stream().filter(e -> e instanceof TurnEvent.ToolResult)
                .map(e -> (TurnEvent.ToolResult) e).findFirst().orElseThrow();
    }

    private static String confirmCallId(List<TurnEvent> events) {
        return events.stream().filter(e -> e instanceof TurnEvent.UiHint h && "confirm".equals(h.kind()))
                .map(e -> String.valueOf(((TurnEvent.UiHint) e).payload().get("callId"))).findFirst().orElseThrow();
    }

    private static LlmClient.HistoryEntry.User lastUser(LlmClient.LlmRequest request) {
        List<LlmClient.HistoryEntry> history = request.history();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof LlmClient.HistoryEntry.User u) return u;
        }
        throw new AssertionError("no user entry");
    }

    /** Answers whatever the test put in {@link #answer}. */
    public static class AskerTool implements ToolHandler<Map<String, Object>, Map<String, Object>> {
        final AtomicReference<Map<String, Object>> answer = new AtomicReference<>(Map.of("ok", true));

        @Override
        public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
            return answer.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        ScriptedLlmClient scriptedLlmClient() {
            return new ScriptedLlmClient();
        }

        @Bean("chipsAskerTool")
        AskerTool chipsAskerTool() {
            return new AskerTool();
        }
    }
}
