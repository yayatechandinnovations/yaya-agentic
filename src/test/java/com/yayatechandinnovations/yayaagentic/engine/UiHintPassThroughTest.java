package com.yayatechandinnovations.yayaagentic.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * yaya-sales design §11.2 — the generic {@code _uiHint} pass-through: a trusted
 * tool response's frame becomes an engine-emitted UiHint; the LLM never sees
 * render plumbing.
 */
class UiHintPassThroughTest {

    @Test
    void splitsAWellFormedFrameAndStripsIt() {
        Map<String, Object> toolValue = Map.of(
                "cart", Map.of("lines", List.of()),
                "_uiHint", Map.of("kind", "cart", "payload", Map.of("cartHandle", "c-1")));

        UiHintPassThrough.Split split = UiHintPassThrough.split(toolValue);

        assertThat(split).isNotNull();
        assertThat(split.kind()).isEqualTo("cart");
        assertThat(split.payload()).containsEntry("cartHandle", "c-1");
        assertThat(split.strippedValue()).isInstanceOfSatisfying(Map.class, stripped -> {
            assertThat(stripped).containsKey("cart");
            assertThat(stripped).doesNotContainKey("_uiHint");
        });
    }

    @Test
    void responsesWithoutAFrameAreUntouched() {
        assertThat(UiHintPassThrough.split(Map.of("plain", "result"))).isNull();
        assertThat(UiHintPassThrough.split("a string result")).isNull();
        assertThat(UiHintPassThrough.split(null)).isNull();
        assertThat(UiHintPassThrough.split(List.of("array", "result"))).isNull();
    }

    @Test
    void malformedFramesAreStrippedButEmitNothing() {
        UiHintPassThrough.Split missingPayload = UiHintPassThrough.split(Map.of(
                "data", 1, "_uiHint", Map.of("kind", "cart")));
        assertThat(missingPayload).isNotNull();
        assertThat(missingPayload.kind()).isNull();
        assertThat(missingPayload.strippedValue()).isEqualTo(Map.of("data", 1));

        UiHintPassThrough.Split notAMap = UiHintPassThrough.split(Map.of(
                "data", 1, "_uiHint", "sneaky-string"));
        assertThat(notAMap).isNotNull();
        assertThat(notAMap.kind()).isNull();
        assertThat(notAMap.strippedValue()).isEqualTo(Map.of("data", 1));
    }
}
