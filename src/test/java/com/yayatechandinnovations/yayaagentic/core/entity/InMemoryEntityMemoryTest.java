package com.yayatechandinnovations.yayaagentic.core.entity;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryEntityMemoryTest {

    private final InMemoryEntityMemory memory = new InMemoryEntityMemory();
    private final Ids.SessionId s1 = new Ids.SessionId("s-1");
    private final Ids.SessionId s2 = new Ids.SessionId("s-2");
    private final Ids.ToolId source = new Ids.ToolId("source_tool");

    @Test
    void resolve_returns_empty_when_session_unknown() {
        assertThat(memory.resolve(s1, "ticketId")).isEmpty();
    }

    @Test
    void capture_then_resolve_singleton() {
        memory.capture(s1, singleton("ticketId", "T-9"));
        assertThat(memory.resolve(s1, "ticketId"))
                .map(EntityRef::value)
                .hasValue("T-9");
    }

    @Test
    void session_isolation() {
        memory.capture(s1, singleton("ticketId", "T-9"));
        assertThat(memory.resolve(s2, "ticketId")).isEmpty();
    }

    @Test
    void collection_capture_appends() {
        memory.capture(s1, collection("lineIds", "L-1"));
        memory.capture(s1, collection("lineIds", "L-2"));
        memory.capture(s1, collection("lineIds", "L-3"));
        EntityRef merged = memory.resolve(s1, "lineIds").orElseThrow();
        assertThat(merged.value()).isInstanceOf(List.class);
        assertThat((List<Object>) merged.value()).containsExactly("L-1", "L-2", "L-3");
    }

    @Test
    void clear_removes_just_that_name() {
        memory.capture(s1, singleton("a", 1));
        memory.capture(s1, singleton("b", 2));
        memory.clear(s1, "a");
        assertThat(memory.resolve(s1, "a")).isEmpty();
        assertThat(memory.resolve(s1, "b")).isPresent();
    }

    @Test
    void clearScope_session_drops_everything() {
        memory.capture(s1, singleton("a", 1));
        memory.capture(s1, singleton("b", 2));
        memory.clearScope(s1, EntityScope.SESSION);
        assertThat(memory.all(s1)).isEmpty();
    }

    @Test
    void all_returns_in_capture_order() {
        memory.capture(s1, singleton("first", 1));
        memory.capture(s1, singleton("second", 2));
        memory.capture(s1, singleton("third", 3));
        assertThat(memory.all(s1))
                .extracting(EntityRef::name)
                .containsExactly("first", "second", "third");
    }

    private EntityRef singleton(String name, Object value) {
        return new EntityRef(name, value, EntityScope.SESSION, EntityKind.SINGLETON,
                true, source, Instant.now(), Map.of());
    }

    private EntityRef collection(String name, Object value) {
        return new EntityRef(name, value, EntityScope.SESSION, EntityKind.COLLECTION,
                true, source, Instant.now(), Map.of());
    }
}
