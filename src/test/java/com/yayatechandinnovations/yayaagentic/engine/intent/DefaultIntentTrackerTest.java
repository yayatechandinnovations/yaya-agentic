package com.yayatechandinnovations.yayaagentic.engine.intent;

import com.yayatechandinnovations.yayaagentic.core.IntentFrame;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultIntentTrackerTest {

    private final DefaultIntentTracker tracker = new DefaultIntentTracker();

    @Test
    void detects_echo_intent_and_extracts_slot() {
        IntentFrame after = tracker.update(List.of(), msg("echo hello"), IntentFrame.empty());

        assertThat(after.label()).isEqualTo("echo");
        assertThat(after.slots()).containsEntry("text", "hello");
        assertThat(after.parkedStack()).isEmpty();
    }

    @Test
    void continuation_merges_slots() {
        IntentFrame first = tracker.update(List.of(), msg("echo hello"), IntentFrame.empty());
        IntentFrame second = tracker.update(List.of(), msg("echo world"), first);

        assertThat(second.label()).isEqualTo("echo");
        assertThat(second.slots()).containsEntry("text", "world");
        assertThat(second.parkedStack()).isEmpty();
    }

    @Test
    void pivot_parks_previous_intent() {
        IntentFrame echoing = tracker.update(List.of(), msg("echo hi"), IntentFrame.empty());
        IntentFrame helping = tracker.update(List.of(), msg("help"), echoing);

        assertThat(helping.label()).isEqualTo("help");
        assertThat(helping.parkedStack()).hasSize(1);
        assertThat(helping.parkedStack().get(0).label()).isEqualTo("echo");
        assertThat(helping.parkedStack().get(0).slots()).containsEntry("text", "hi");
    }

    @Test
    void resume_pops_parked_intent_when_label_matches() {
        IntentFrame echoing = tracker.update(List.of(), msg("echo hi"), IntentFrame.empty());
        IntentFrame helping = tracker.update(List.of(), msg("help"), echoing);
        IntentFrame backToEcho = tracker.update(List.of(), msg("echo bye"), helping);

        assertThat(backToEcho.label()).isEqualTo("echo");
        assertThat(backToEcho.slots()).containsEntry("text", "bye");
        assertThat(backToEcho.parkedStack()).isEmpty();
    }

    @Test
    void unclassified_message_keeps_current_intent() {
        IntentFrame echoing = tracker.update(List.of(), msg("echo hello"), IntentFrame.empty());
        IntentFrame unchanged = tracker.update(List.of(), msg("yes please"), echoing);

        assertThat(unchanged.label()).isEqualTo("echo");
        assertThat(unchanged.slots()).containsEntry("text", "hello");
    }

    private static UserMessage msg(String text) {
        return new UserMessage(text, Map.of());
    }
}
