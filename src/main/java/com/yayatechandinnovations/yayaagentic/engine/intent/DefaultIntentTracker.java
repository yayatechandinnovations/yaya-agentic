package com.yayatechandinnovations.yayaagentic.engine.intent;

import com.yayatechandinnovations.yayaagentic.core.IntentFrame;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.engine.IntentTracker;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * M2-A heuristic intent tracker. Classifies the incoming message into one
 * of a small set of intents and decides between three transitions:
 * <ul>
 *   <li><b>continuation</b> — same intent as before, merge slots.</li>
 *   <li><b>pivot</b> — different intent, park current.</li>
 *   <li><b>resume</b> — same intent as the top of the parked stack,
 *       pop it back.</li>
 * </ul>
 * LLM-assisted classification (for the ambiguous middle) lands in M2-B.
 * <p>
 * Returns a NEW {@link IntentFrame} every turn — callers persist it.
 */
@Component
@ConditionalOnMissingBean(value = IntentTracker.class, ignored = DefaultIntentTracker.class)
public class DefaultIntentTracker implements IntentTracker {

    @Override
    public IntentFrame update(List<Turn> history,
                              UserMessage incoming,
                              IntentFrame current) {
        Classification c = classify(incoming == null ? "" : incoming.text());
        IntentFrame frame = current == null ? IntentFrame.empty() : current;

        // No intent detected — keep what we had so a follow-up like "yes"
        // can still slot into the active intent.
        if (c.label == null) return frame;

        if (Objects.equals(frame.label(), c.label)) {
            return continuation(frame, c.slots);
        }
        var parked = frame.parkedStack();
        if (parked != null && !parked.isEmpty() && Objects.equals(parked.get(0).label(), c.label)) {
            return resumeTop(frame, c.slots);
        }
        return pivot(frame, c.label, c.slots);
    }

    // ---- transitions ----------------------------------------------------

    private IntentFrame continuation(IntentFrame current, Map<String, Object> slotsIn) {
        Map<String, Object> merged = new LinkedHashMap<>(current.slots() == null ? Map.of() : current.slots());
        merged.putAll(slotsIn);
        return new IntentFrame(current.label(), merged, current.parkedStack());
    }

    private IntentFrame pivot(IntentFrame current, String newLabel, Map<String, Object> slotsIn) {
        List<IntentFrame.ParkedIntent> stack = new ArrayList<>(
                current.parkedStack() == null ? List.of() : current.parkedStack());
        if (current.label() != null) {
            stack.add(0, new IntentFrame.ParkedIntent(
                    current.label(),
                    current.slots() == null ? Map.of() : Map.copyOf(current.slots()),
                    "pivot to " + newLabel));
        }
        return new IntentFrame(newLabel, new LinkedHashMap<>(slotsIn), stack);
    }

    private IntentFrame resumeTop(IntentFrame current, Map<String, Object> slotsIn) {
        List<IntentFrame.ParkedIntent> stack = new ArrayList<>(current.parkedStack());
        IntentFrame.ParkedIntent top = stack.remove(0);
        Map<String, Object> merged = new LinkedHashMap<>(top.slots() == null ? Map.of() : top.slots());
        merged.putAll(slotsIn);
        return new IntentFrame(top.label(), merged, stack);
    }

    // ---- classification (heuristic, intentionally tiny) -----------------

    private static Classification classify(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) return Classification.none();

        String lower = trimmed.toLowerCase(Locale.ROOT);
        // Bare "echo" / "/echo" — recognise the intent but leave the slot
        // empty so the executor's schema validator triggers elicitation.
        if (lower.equals("/echo") || lower.equals("echo")) {
            return new Classification("echo", Map.of());
        }
        if (lower.startsWith("/echo ") || lower.startsWith("echo ")) {
            int prefix = lower.startsWith("/") ? 6 : 5;
            return new Classification("echo", Map.of("text", trimmed.substring(prefix).trim()));
        }
        if (lower.equals("help") || lower.equals("what can you do")
                || lower.endsWith("?") && lower.contains("can you do")) {
            return new Classification("help", Map.of());
        }
        if (lower.equals("hello") || lower.equals("hi") || lower.equals("hey")) {
            return new Classification("greet", Map.of());
        }
        return Classification.none();
    }

    private record Classification(String label, Map<String, Object> slots) {
        static Classification none() { return new Classification(null, Map.of()); }
    }
}
