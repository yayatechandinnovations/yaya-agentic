package com.yayatechandinnovations.yayaagentic.testsupport;

import com.yayatechandinnovations.yayaagentic.llm.LlmClient;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A deterministic {@link LlmClient}: each call plays the next scripted round
 * and remembers the request it was given. With no script left it answers
 * "ok". Every round reports {@link #ROUND_USAGE} unless the script says otherwise.
 */
public class ScriptedLlmClient implements LlmClient {

    public static final Usage ROUND_USAGE = new Usage(100, 20);

    private final CopyOnWriteArrayList<LlmRequest> requests = new CopyOnWriteArrayList<>();
    private final Deque<Flux<LlmEvent>> rounds = new ArrayDeque<>();
    private volatile String alwaysCallTool;
    private volatile boolean failNext;
    private int generated;

    public synchronized void reset() {
        requests.clear();
        rounds.clear();
        alwaysCallTool = null;
        failNext = false;
    }

    /** One round that proposes a single tool call. */
    public synchronized ScriptedLlmClient tool(String callId, String tool, Map<String, Object> args) {
        rounds.add(Flux.just(new LlmEvent.ToolUseProposal(callId, tool, args),
                new LlmEvent.Done(StopReason.TOOL_USE, ROUND_USAGE)));
        return this;
    }

    /** One round that answers with text and ends the turn. */
    public synchronized ScriptedLlmClient say(String text) {
        rounds.add(Flux.just(new LlmEvent.TokenChunk(text), new LlmEvent.Done(StopReason.END_TURN, ROUND_USAGE)));
        return this;
    }

    /** One round played from {@code events} as given: a test can pause it, or watch it being cancelled. */
    public synchronized ScriptedLlmClient round(Flux<LlmEvent> events) {
        rounds.add(events);
        return this;
    }

    /** Every round proposes {@code tool} again, forever. */
    public void alwaysCall(String tool) {
        this.alwaysCallTool = tool;
    }

    /** The next round fails before producing anything. */
    public void failNextRound() {
        this.failNext = true;
    }

    public List<LlmRequest> requests() {
        return requests;
    }

    public LlmRequest lastRequest() {
        return requests.get(requests.size() - 1);
    }

    @Override
    public synchronized Flux<LlmEvent> stream(LlmRequest request) {
        requests.add(request);
        if (failNext) {
            failNext = false;
            return Flux.error(new IllegalStateException("scripted model failure"));
        }
        if (alwaysCallTool != null) {
            return Flux.just(
                    new LlmEvent.ToolUseProposal("loop-" + generated++, alwaysCallTool,
                            Map.of("text", "n" + generated)),
                    new LlmEvent.Done(StopReason.TOOL_USE, ROUND_USAGE));
        }
        Flux<LlmEvent> next = rounds.poll();
        if (next == null) {
            next = Flux.just(new LlmEvent.TokenChunk("ok"), new LlmEvent.Done(StopReason.END_TURN, ROUND_USAGE));
        }
        return next;
    }
}
