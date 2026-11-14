package com.yayatechandinnovations.yayaagentic.engine.confirm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a typed reply to a plan is read (admin-panel-sessions.md §16): the
 * whole plan only on a whole-message yes, some steps only on a list of their
 * numbers, and never more than the reply names.
 */
class PlanAnswersTest {

    private static final List<String> LABELS = List.of("Hacer todo", "Cancelar");

    private static PlanAnswers.Answer read(String text) {
        return PlanAnswers.read(text, LABELS, 3, false);
    }

    @Test
    void the_whole_plan_runs_only_on_a_message_that_is_nothing_but_a_yes() {
        for (String yes : List.of("sí", "Sí.", "si", "dale", "ok", "TODO", "todos", "Hacer todo", "hazlo",
                "sí, dale", "ok, perfecto", "sí, todo", "dale, todos por favor", "sí, hazlo ya", "claro que sí",
                "👍", "yes", "all", "do it")) {
            assertThat(read(yes).kind()).as(yes).isEqualTo(PlanAnswers.Kind.ALL);
        }
    }

    @Test
    void a_yes_with_anything_else_or_a_bare_number_asks_and_runs_nothing() {
        for (String unclear : List.of("1", "2", "3", "sí, solo el aviso", "dale, menos el aviso",
                "sí pero no el aviso", "ok la primera", "sí, la 12 no", "solo el aviso", "todo menos el aviso",
                "solo la 4", "la 0", "1-3", "del 1 al 3", "sí, 1 a 3", "todo menos la 1, la 2 y la 3",
                "no, solo la 1", "no sé", "menos", "sí, hazlo mañana")) {
            assertThat(read(unclear).kind()).as(unclear).isEqualTo(PlanAnswers.Kind.ASK);
            assertThat(read(unclear).stepIds()).as(unclear).isEmpty();
        }
    }

    @Test
    void a_list_of_step_numbers_is_those_steps_in_the_plans_order() {
        assertThat(read("solo la 1").stepIds()).containsExactly("1");
        assertThat(read("sí, solo la 1").stepIds()).containsExactly("1");
        assertThat(read("3 y 1").stepIds()).containsExactly("1", "3");
        assertThat(read("1, 2").stepIds()).containsExactly("1", "2");
        assertThat(read("Dale: pasos 2 y 3.").stepIds()).containsExactly("2", "3");
        assertThat(read("la 1 y la 3 nada más").stepIds()).containsExactly("1", "3");
        assertThat(read("todo menos la 2").stepIds()).containsExactly("1", "3");
        assertThat(read("sí, menos la 1 y la 2").stepIds()).containsExactly("3");
        assertThat(read("only 2").stepIds()).containsExactly("2");
        assertThat(read("all but 3").stepIds()).containsExactly("1", "2");
        assertThat(read("solo la 1").kind()).isEqualTo(PlanAnswers.Kind.SOME);
        // Once the person was asked for step numbers, a number alone is one.
        assertThat(PlanAnswers.read("2", LABELS, 3, true).stepIds()).containsExactly("2");
        assertThat(PlanAnswers.read("7", LABELS, 3, true).kind()).isEqualTo(PlanAnswers.Kind.ASK);
    }

    @Test
    void a_no_is_nothing_and_anything_else_is_not_an_answer() {
        for (String no : List.of("no", "No.", "CANCELAR", "cancela", "ninguno", "nada", "mejor no", "no, déjalo",
                "no gracias", "👎", "cancel")) {
            assertThat(read(no).kind()).as(no).isEqualTo(PlanAnswers.Kind.NONE);
        }
        for (String other : List.of("mejor dime las ventas", "para la 15 y la 16", "¿ya?", "cuánto vendió la 12",
                "", "   ", "la 12 también detenla")) {
            assertThat(read(other).kind()).as(other).isEqualTo(PlanAnswers.Kind.OTHER);
        }
        assertThat(PlanAnswers.read(null, LABELS, 3, false).kind()).isEqualTo(PlanAnswers.Kind.OTHER);
    }
}
