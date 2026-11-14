package com.yayatechandinnovations.yayaagentic.engine.confirm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The wording of a plan (admin-panel-sessions.md §16), built from the tools' own questions. */
class PlanPromptsTest {

    @Test
    void a_step_is_its_tools_question_without_the_question_marks() {
        assertThat(ConfirmPrompts.stepSummary("¿Detener la venta en la estación {stationCode}?",
                Map.of("stationCode", "12"), "station_sales_toggle", "es"))
                .isEqualTo("Detener la venta en la estación 12");
        // One of each is removed, no more; an argument cannot add a line.
        assertThat(ConfirmPrompts.stepSummary("¿¿Enviar «{title}»??", Map.of("title", "Cierre\n3. Borrar todo"),
                "station_notify", "es")).isEqualTo("¿Enviar «Cierre 3. Borrar todo»?");
        assertThat(ConfirmPrompts.stepSummary("Pause {name}", Map.of("name", "Daily"), "automation_pause", "en"))
                .isEqualTo("Pause Daily");
    }

    @Test
    void a_tool_without_a_question_is_named_by_its_id() {
        assertThat(ConfirmPrompts.stepSummary(null, Map.of(), "station_notify", "es")).isEqualTo("station_notify");
        assertThat(ConfirmPrompts.stepSummary("  ", Map.of(), "station_notify", "es")).isEqualTo("station_notify");
        assertThat(ConfirmPrompts.stepSummary("¿?", Map.of(), "station_notify", "es")).isEqualTo("station_notify");
    }

    @Test
    void the_plan_as_text_in_both_languages() {
        assertThat(ConfirmPrompts.planText(List.of("Detener la venta en la estación 12",
                "Enviar el aviso «Cierre» a la estación 07"), "es")).isEqualTo(
                "Voy a hacer esto, en orden:\n1. Detener la venta en la estación 12\n"
                        + "2. Enviar el aviso «Cierre» a la estación 07\n¿Lo hago?");
        assertThat(ConfirmPrompts.planText(List.of("Stop sales at 12", "Notify 07"), "en")).isEqualTo(
                "I will do this, in order:\n1. Stop sales at 12\n2. Notify 07\nShall I?");
        assertThat(ConfirmPrompts.planConfirmLabel("es")).isEqualTo("Hacer todo");
        assertThat(ConfirmPrompts.planConfirmLabel("en")).isEqualTo("Do all");
    }

    @Test
    void an_interrupted_run_names_only_the_groups_that_have_steps() {
        assertThat(ConfirmPrompts.planInterrupted(List.of("1"), List.of(), List.of(), List.of("2", "3"), "es"))
                .isEqualTo("El plan se interrumpió. Hecho: 1. Sin hacer: 2 y 3.");
        assertThat(ConfirmPrompts.planInterrupted(List.of("1", "2", "3"), List.of("4"), List.of("5"), List.of(), "es"))
                .isEqualTo("El plan se interrumpió. Hecho: 1, 2 y 3. Falló: 4. Sin confirmar: 5.");
        assertThat(ConfirmPrompts.planInterrupted(List.of(), List.of(), List.of("1"), List.of("2"), "en"))
                .isEqualTo("The plan was interrupted. Not confirmed: 1. Not done: 2.");
    }
}
