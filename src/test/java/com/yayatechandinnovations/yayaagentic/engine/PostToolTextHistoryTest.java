package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.HelloWorldProfileBootstrap;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import com.yayatechandinnovations.yayaagentic.llm.LlmClient.StopReason;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.recorder.ConversationRecorder;
import com.yayatechandinnovations.yayaagentic.recorder.TurnRange;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a turn that used tools leaves behind: the reply the model wrote after
 * the tool results must be stored, and must be what the model reads back as
 * its own previous answer on the next turn.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, AgenticMultiRoundTest.ScriptedLlmConfig.class})
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "spring.main.allow-bean-definition-overriding=true"
})
class PostToolTextHistoryTest {

    @Autowired ConversationEngine engine;
    @Autowired AgenticMultiRoundTest.ScriptedLlm llm;
    @Autowired @Qualifier("postgresConversationRecorder") ConversationRecorder recorder;

    @Test
    void reply_written_after_a_tool_result_is_stored_and_replayed_next_turn() {
        llm.scriptRounds(
                List.of(new LlmClient.LlmEvent.ToolUseProposal("call-1", "echo", Map.of("text", "uno")),
                        new LlmClient.LlmEvent.Done(StopReason.TOOL_USE)),
                List.of(new LlmClient.LlmEvent.TokenChunk("Listo: el eco devolvió uno."),
                        new LlmClient.LlmEvent.Done(StopReason.END_TURN)),
                List.of(new LlmClient.LlmEvent.TokenChunk("De nada."),
                        new LlmClient.LlmEvent.Done(StopReason.END_TURN)));
        Ids.SessionId sid = startSession();

        drain(sid, "haz eco de uno");

        List<Turn> stored = recorder.loadTurns(sid, TurnRange.all());
        assertThat(stored).extracting(Turn::role)
                .containsExactly(Turn.Role.USER, Turn.Role.ASSISTANT, Turn.Role.ASSISTANT);
        assertThat(stored.get(1).toolResults()).hasSize(1);
        assertThat(stored.get(2).content()).isEqualTo("Listo: el eco devolvió uno.");

        drain(sid, "gracias");

        // The latest model call is the next turn's first round.
        List<LlmClient.HistoryEntry> history = llm.requests().get(llm.requests().size() - 1).history();
        assertThat(history).hasSize(5);
        assertThat(history.get(0)).isEqualTo(new LlmClient.HistoryEntry.User("haz eco de uno"));
        assertThat(((LlmClient.HistoryEntry.Assistant) history.get(1)).toolCalls()).hasSize(1);
        assertThat(history.get(2)).isInstanceOf(LlmClient.HistoryEntry.ToolResults.class);
        assertThat(history.get(3)).isEqualTo(
                new LlmClient.HistoryEntry.Assistant("Listo: el eco devolvió uno.", List.of()));
        assertThat(history.get(4)).isEqualTo(new LlmClient.HistoryEntry.User("gracias"));
    }

    @Test
    void every_tool_round_of_a_turn_is_kept_and_the_user_message_is_stored_once() {
        llm.scriptRounds(
                List.of(new LlmClient.LlmEvent.ToolUseProposal("call-a", "echo", Map.of("text", "a")),
                        new LlmClient.LlmEvent.Done(StopReason.TOOL_USE)),
                List.of(new LlmClient.LlmEvent.ToolUseProposal("call-b", "echo", Map.of("text", "b")),
                        new LlmClient.LlmEvent.Done(StopReason.TOOL_USE)),
                List.of(new LlmClient.LlmEvent.TokenChunk("Hice los dos."),
                        new LlmClient.LlmEvent.Done(StopReason.END_TURN)),
                List.of(new LlmClient.LlmEvent.TokenChunk("Ok."),
                        new LlmClient.LlmEvent.Done(StopReason.END_TURN)));
        Ids.SessionId sid = startSession();

        drain(sid, "dos ecos");

        List<Turn> stored = recorder.loadTurns(sid, TurnRange.all());
        assertThat(stored).extracting(Turn::role).containsExactly(
                Turn.Role.USER, Turn.Role.ASSISTANT, Turn.Role.ASSISTANT, Turn.Role.ASSISTANT);
        assertThat(stored).extracting(Turn::index).containsExactly(0, 1, 2, 3);
        assertThat(stored.get(1).toolCalls().get(0).callId()).isEqualTo("call-a");
        assertThat(stored.get(2).toolCalls().get(0).callId()).isEqualTo("call-b");
        assertThat(stored.get(3).content()).isEqualTo("Hice los dos.");

        drain(sid, "sigue");

        List<LlmClient.HistoryEntry> history = llm.requests().get(llm.requests().size() - 1).history();
        assertThat(history.stream().filter(e -> e instanceof LlmClient.HistoryEntry.User)
                .map(e -> ((LlmClient.HistoryEntry.User) e).content()))
                .containsExactly("dos ecos", "sigue");
        assertThat(history.stream().filter(e -> e instanceof LlmClient.HistoryEntry.ToolResults)).hasSize(2);
    }

    private Ids.SessionId startSession() {
        return engine.start(
                new StartConversationRequest(
                        HelloWorldProfileBootstrap.DEFAULT_TENANT,
                        Optional.of(HelloWorldProfileBootstrap.HELLO_WORLD_PROFILE),
                        "web", Map.of()),
                new AuthContext(HelloWorldProfileBootstrap.DEFAULT_TENANT, Map.of(), Optional.empty())
        ).session().id();
    }

    private List<TurnEvent> drain(Ids.SessionId sid, String text) {
        List<TurnEvent> out = new ArrayList<>();
        engine.send(sid, new UserMessage(text, Map.of()),
                new AuthContext(HelloWorldProfileBootstrap.DEFAULT_TENANT, Map.of(), Optional.empty()))
                .doOnNext(out::add)
                .blockLast();
        return out;
    }
}
