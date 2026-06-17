package com.yayatechandinnovations.yayaagentic.tool.dispatch;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityKind;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityRef;
import com.yayatechandinnovations.yayaagentic.core.entity.EntityScope;
import com.yayatechandinnovations.yayaagentic.core.entity.InMemoryEntityMemory;
import com.yayatechandinnovations.yayaagentic.tool.BindingMode;
import com.yayatechandinnovations.yayaagentic.tool.EntityBinding;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EntityBinderTest {

    private final InMemoryEntityMemory memory = new InMemoryEntityMemory();
    private final Ids.SessionId session = new Ids.SessionId("s-1");
    private final Ids.ToolId source = new Ids.ToolId("source_tool");

    @Test
    void fill_if_missing_fills_when_arg_absent() {
        memory.capture(session, ref("ticketId", "T-9"));
        EntityBinder.Result r = EntityBinder.apply(
                Map.of(),
                List.of(EntityBinding.fillIfMissing("ticketId", "ticketId")),
                memory, session);
        assertThat(r.args()).containsEntry("ticketId", "T-9");
        assertThat(r.bound()).containsExactly("ticketId");
    }

    @Test
    void fill_if_missing_does_not_overwrite_present_arg() {
        memory.capture(session, ref("ticketId", "T-9"));
        EntityBinder.Result r = EntityBinder.apply(
                Map.of("ticketId", "T-LLM-PROPOSED"),
                List.of(EntityBinding.fillIfMissing("ticketId", "ticketId")),
                memory, session);
        assertThat(r.args()).containsEntry("ticketId", "T-LLM-PROPOSED");
        assertThat(r.bound()).isEmpty();
    }

    @Test
    void override_replaces_arg_even_when_llm_supplied() {
        memory.capture(session, ref("ticketId", "T-9"));
        EntityBinder.Result r = EntityBinder.apply(
                Map.of("ticketId", "T-HALLUCINATED"),
                List.of(EntityBinding.override("ticketId", "ticketId")),
                memory, session);
        assertThat(r.args()).containsEntry("ticketId", "T-9");
        assertThat(r.bound()).containsExactly("ticketId");
    }

    @Test
    void override_with_empty_memory_does_not_blank_an_existing_arg() {
        EntityBinder.Result r = EntityBinder.apply(
                Map.of("ticketId", "T-LLM"),
                List.of(EntityBinding.override("ticketId", "ticketId")),
                memory, session);
        assertThat(r.args()).containsEntry("ticketId", "T-LLM");
        assertThat(r.bound()).isEmpty();
    }

    @Test
    void blank_string_treated_as_missing_for_fill_if_missing() {
        memory.capture(session, ref("ticketId", "T-9"));
        EntityBinder.Result r = EntityBinder.apply(
                Map.of("ticketId", "   "),
                List.of(EntityBinding.fillIfMissing("ticketId", "ticketId")),
                memory, session);
        assertThat(r.args()).containsEntry("ticketId", "T-9");
        assertThat(r.bound()).containsExactly("ticketId");
    }

    @Test
    void no_bindings_returns_args_unchanged() {
        EntityBinder.Result r = EntityBinder.apply(
                Map.of("a", 1, "b", 2),
                List.of(),
                memory, session);
        assertThat(r.args()).isEqualTo(Map.of("a", 1, "b", 2));
        assertThat(r.bound()).isEmpty();
    }

    @Test
    void null_args_handled_as_empty() {
        memory.capture(session, ref("ticketId", "T-9"));
        EntityBinder.Result r = EntityBinder.apply(
                null,
                List.of(EntityBinding.fillIfMissing("ticketId", "ticketId")),
                memory, session);
        assertThat(r.args()).containsEntry("ticketId", "T-9");
    }

    @Test
    void required_mode_skipped_when_memory_empty_so_elicitation_can_catch_it() {
        // REQUIRED isn't supposed to invent a value; it leaves the arg
        // absent so the downstream schema validator / elicitation flow
        // surfaces the missing field. Asserting the no-op here.
        EntityBinder.Result r = EntityBinder.apply(
                Map.of(),
                List.of(new EntityBinding("ticketId", "ticketId", BindingMode.REQUIRED)),
                memory, session);
        assertThat(r.args()).doesNotContainKey("ticketId");
        assertThat(r.bound()).isEmpty();
    }

    private EntityRef ref(String name, Object value) {
        return new EntityRef(name, value, EntityScope.SESSION, EntityKind.SINGLETON,
                /* visibleToLlm */ true, source, Instant.now(), Map.of());
    }
}
