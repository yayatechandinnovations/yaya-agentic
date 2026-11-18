package com.yayatechandinnovations.yayaagentic.engine.handoff;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HandoffBlockTest {

    private static final Ids.SessionId SID = new Ids.SessionId("s-1");

    @Test
    void renders_the_summary_then_one_line_per_turn_with_hour_channel_and_role() {
        List<Turn> turns = List.of(
                turn(0, Turn.Role.USER, "Qué pasó con el ticket 4411", Map.of("channel", "whatsapp"),
                        Instant.parse("2026-11-18T14:02:11Z")),
                turn(1, Turn.Role.ASSISTANT, "El ticket 4411 está\nconfirmado.", Map.of(),
                        Instant.parse("2026-11-18T14:02:40Z")));

        HandoffBlock block = HandoffBlock.build("whatsapp", "Habló del ticket 4411.", turns);

        assertThat(block.content())
                .startsWith("<channel_handoff from=\"whatsapp\" horas=\"UTC\">\nResumen previo: Habló del ticket 4411.\n---\n")
                .contains("[14:02 whatsapp] Usuario: Qué pasó con el ticket 4411\n")
                .contains("[14:02 whatsapp] Asistente: El ticket 4411 está confirmado.\n")
                .contains("</channel_handoff>\n" + HandoffBlock.instruction("whatsapp"));
        assertThat(HandoffBlock.instruction("whatsapp"))
                .contains("WhatsApp").contains("confirmación, un plan o un código").contains("como confirmado");
        assertThat(block.carried()).hasSize(2);
        assertThat(block.throughTurnIdx()).isEqualTo(1);
    }

    @Test
    void keeps_the_last_twelve_user_and_assistant_turns_and_skips_system_and_empty_ones() {
        List<Turn> turns = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            turns.add(turn(i, i % 2 == 0 ? Turn.Role.USER : Turn.Role.ASSISTANT, "m" + i, Map.of(), Instant.EPOCH));
        }
        turns.add(turn(20, Turn.Role.SYSTEM, "sistema", Map.of("origin", "SYSTEM_PARTICIPANT"), Instant.EPOCH));
        turns.add(turn(21, Turn.Role.ASSISTANT, "", Map.of(), Instant.EPOCH));

        HandoffBlock block = HandoffBlock.build("chat", null, turns);

        assertThat(block.carried()).extracting(Turn::index)
                .containsExactly(8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19);
        assertThat(block.content()).doesNotContain("sistema").doesNotContain("m7\n").contains("m8\n");
        assertThat(block.throughTurnIdx()).isEqualTo(21);
    }

    @Test
    void over_budget_drops_the_oldest_lines_and_keeps_the_newest() {
        String big = "x".repeat(4_000);
        List<Turn> turns = List.of(
                turn(0, Turn.Role.USER, "primero " + big, Map.of(), Instant.EPOCH),
                turn(1, Turn.Role.ASSISTANT, "segundo " + big, Map.of(), Instant.EPOCH),
                turn(2, Turn.Role.USER, "tercero " + big, Map.of(), Instant.EPOCH),
                turn(3, Turn.Role.ASSISTANT, "último", Map.of(), Instant.EPOCH));

        HandoffBlock block = HandoffBlock.build("whatsapp", null, turns);

        assertThat(block.content().length()).isLessThanOrEqualTo(HandoffBlock.BUDGET_CHARS);
        assertThat(block.carried()).extracting(Turn::index).containsExactly(2, 3);
        assertThat(block.content()).contains("tercero").contains("último").doesNotContain("primero");
    }

    @Test
    void a_summary_gives_way_to_the_newest_turn_when_both_do_not_fit() {
        String summary = "resumen ".repeat(1_500);
        List<Turn> turns = List.of(turn(0, Turn.Role.USER, "la única línea", Map.of(), Instant.EPOCH));

        HandoffBlock block = HandoffBlock.build("whatsapp", summary, turns);

        assertThat(block.content().length()).isLessThanOrEqualTo(HandoffBlock.BUDGET_CHARS + 8);
        assertThat(block.content()).contains("la única línea").contains("Resumen previo: …");
        assertThat(block.carried()).hasSize(1);
    }

    @Test
    void hints_and_attachments_become_one_line_notes_and_angle_brackets_are_neutralised() {
        Map<String, Object> meta = Map.of(
                "uiHints", List.of(
                        Map.of("kind", "confirm", "payload", Map.of("summary", "¿Anular el 4411?")),
                        Map.of("kind", "open_screen", "payload", Map.of("screenId", "sales.controls"))),
                "attachments", List.of(Map.of("filename", "ticket.jpg", "kind", "IMAGE")));
        List<Turn> turns = List.of(
                turn(0, Turn.Role.USER, "<script>", meta, Instant.EPOCH),
                turn(1, Turn.Role.ASSISTANT, "", Map.of("uiHints", List.of(Map.of("kind", "table"))), Instant.EPOCH));

        HandoffBlock block = HandoffBlock.build("whatsapp", null, turns);

        assertThat(block.content())
                .contains("Usuario: ‹script› (mostró una confirmación: «¿Anular el 4411?») "
                        + "(abrió la pantalla sales.controls) (adjunto: ticket.jpg, image)")
                .contains("Asistente: (mostró table)");
    }

    @Test
    void pending_frames_are_read_the_way_latest_reads_them() {
        Instant now = Instant.parse("2026-11-18T14:00:00Z");
        assertThat(PendingFrame.of(null, now)).isNull();
        assertThat(PendingFrame.of(Map.of(), now)).isNull();
        assertThat(PendingFrame.of(Map.of("pending_confirm", Map.of("frame", Map.of("callId", "c1"))), now))
                .isEqualTo("confirm");
        assertThat(PendingFrame.of(Map.of("pending_confirm", Map.of("frame", Map.of())), now)).isNull();
        assertThat(PendingFrame.of(Map.of("pending_plan", Map.of("state", "PENDING", "frame", Map.of("x", 1))), now))
                .isEqualTo("plan");
        assertThat(PendingFrame.of(Map.of("pending_plan", Map.of("state", "RUNNING", "frame", Map.of("x", 1))), now))
                .isNull();
        assertThat(PendingFrame.of(Map.of("pending_secret", Map.of("frame", Map.of("callId", "s1"),
                "expiresAt", "2026-11-18T14:05:00Z")), now)).isEqualTo("secret");
        assertThat(PendingFrame.of(Map.of("pending_secret", Map.of("frame", Map.of("callId", "s1"),
                "expiresAt", "2026-11-18T13:05:00Z")), now)).isNull();
    }

    @Test
    void previews_are_cut_to_160_characters_on_one_line() {
        assertThat(ChannelHandoffService.cut(null)).isNull();
        assertThat(ChannelHandoffService.cut("corto\naquí")).isEqualTo("corto aquí");
        String cut = ChannelHandoffService.cut("y".repeat(300));
        assertThat(cut).hasSize(160).endsWith("…");
    }

    private static Turn turn(int idx, Turn.Role role, String text, Map<String, Object> meta, Instant at) {
        return new Turn(new Ids.TurnId("t-" + idx), SID, idx, role, text, List.of(), List.of(), List.of(), null,
                meta, at);
    }
}
