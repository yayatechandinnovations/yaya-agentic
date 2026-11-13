package com.yayatechandinnovations.yayaagentic.outreach;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The chat inbox stores the drain projection of actions and reads it back. */
class ActionItemJsonTest {

    @Test
    void drain_projection_round_trips_with_the_action_id() throws Exception {
        List<ActionItem> items = new ObjectMapper().readValue(
                "[{\"label\":\"Aprobar\",\"send\":\"Aprobar\",\"style\":\"primary\",\"actionId\":\"a1\"},"
                        + "{\"label\":\"Ver\",\"send\":\"Ver UR-1\",\"style\":\"neutral\"}]",
                new TypeReference<List<ActionItem>>() {});

        assertThat(items).extracting(ActionItem::actionId).containsExactly("a1", null);
        assertThat(items.get(1).send()).isEqualTo("Ver UR-1");
        assertThat(items.get(0).bound()).isFalse();
    }
}
