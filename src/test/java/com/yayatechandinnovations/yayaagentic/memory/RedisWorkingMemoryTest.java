package com.yayatechandinnovations.yayaagentic.memory;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class RedisWorkingMemoryTest {

    @Autowired WorkingMemory memory;

    @Test
    void put_then_get_round_trips_typed_values() {
        Ids.SessionId sid = new Ids.SessionId(UUID.randomUUID().toString());

        memory.merge(sid, Map.of(
                "intent_label", "echo",
                "count", 7,
                "options", List.of("a", "b")));

        Map<String, Object> read = memory.get(sid);
        assertThat(read).containsEntry("intent_label", "echo");
        assertThat(read).containsEntry("count", 7);
        assertThat(read.get("options")).isEqualTo(List.of("a", "b"));
    }

    @Test
    void merge_overwrites_only_the_provided_keys() {
        Ids.SessionId sid = new Ids.SessionId(UUID.randomUUID().toString());

        memory.merge(sid, Map.of("a", 1, "b", 2));
        memory.merge(sid, Map.of("b", 20, "c", 30));

        Map<String, Object> read = memory.get(sid);
        assertThat(read).containsEntry("a", 1);
        assertThat(read).containsEntry("b", 20);
        assertThat(read).containsEntry("c", 30);
    }

    @Test
    void clear_drops_the_bag() {
        Ids.SessionId sid = new Ids.SessionId(UUID.randomUUID().toString());
        memory.merge(sid, Map.of("x", "y"));

        memory.clear(sid);

        assertThat(memory.get(sid)).isEmpty();
    }
}
