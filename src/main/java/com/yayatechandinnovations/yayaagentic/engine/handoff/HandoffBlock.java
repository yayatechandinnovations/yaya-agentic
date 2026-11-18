package com.yayatechandinnovations.yayaagentic.engine.handoff;

import com.yayatechandinnovations.yayaagentic.core.Turn;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The context copied from one channel's conversation into another's: the
 * source's rolling summary plus its last {@link #MAX_TURNS} user/assistant
 * turns, each on one line, cut to about {@link #BUDGET_TOKENS} tokens with
 * the newest kept — followed by the fixed instruction that tells the model
 * the person continues here and that nothing from the other channel counts
 * as confirmed.
 *
 * @param content        what the SYSTEM turn stores: the block and the instruction
 * @param carried        the source turns whose lines made it into the block, oldest first
 * @param throughTurnIdx the index of the last source turn looked at
 */
public record HandoffBlock(String content, List<Turn> carried, int throughTurnIdx) {

    public static final String ORIGIN = "CHANNEL_HANDOFF";
    public static final int MAX_TURNS = 12;
    public static final int BUDGET_TOKENS = 2_000;
    /** A coarse estimate is enough: the budget bounds a prompt, it is not billed. */
    static final int CHARS_PER_TOKEN = 4;
    static final int BUDGET_CHARS = BUDGET_TOKENS * CHARS_PER_TOKEN;

    static final String SUMMARY_PREFIX = "Resumen previo: ";
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

    public static String instruction(String fromChannel) {
        return "La persona continúa aquí lo que estaba haciendo en " + humanChannel(fromChannel)
                + ". Si allá quedó pendiente una confirmación, un plan o un código, dilo en una línea y "
                + "propónlo de nuevo aquí si la persona quiere. Nada de lo que pasó en el otro canal cuenta "
                + "como confirmado: toda acción se vuelve a proponer y confirmar aquí.";
    }

    public static HandoffBlock build(String fromChannel, String summaryText, List<Turn> sourceTurns) {
        String channel = fromChannel == null || fromChannel.isBlank() ? "app" : fromChannel;
        List<Turn> turns = sourceTurns == null ? List.of() : sourceTurns;
        int through = turns.stream().mapToInt(Turn::index).max().orElse(-1);

        List<Turn> candidates = new ArrayList<>();
        for (int i = turns.size() - 1; i >= 0 && candidates.size() < MAX_TURNS; i--) {
            Turn t = turns.get(i);
            if (t.role() != Turn.Role.USER && t.role() != Turn.Role.ASSISTANT) continue;
            if (line(t, channel).isEmpty()) continue;
            candidates.add(0, t);
        }

        String header = "<channel_handoff from=\"" + channel + "\" horas=\"UTC\">\n";
        String footer = "</channel_handoff>\n" + instruction(channel);
        String summary = summaryText == null || summaryText.isBlank() ? "" : SUMMARY_PREFIX + summaryText.strip();
        int fixed = header.length() + footer.length() + (summary.isEmpty() ? 0 : 5);

        List<String> lines = new ArrayList<>();
        List<Turn> carried = new ArrayList<>();
        int used = fixed + summary.length();
        // Newest first, so the oldest lines are the ones that do not fit.
        for (int i = candidates.size() - 1; i >= 0; i--) {
            String line = line(candidates.get(i), channel);
            if (used + line.length() + 1 > BUDGET_CHARS && !carried.isEmpty()) break;
            if (used + line.length() + 1 > BUDGET_CHARS) {
                // Even the newest line alone is over: the summary gives way to it.
                int room = BUDGET_CHARS - fixed - line.length() - 1 - SUMMARY_PREFIX.length();
                summary = room <= 1 || summary.isEmpty() ? ""
                        : SUMMARY_PREFIX + tailOf(summary.substring(SUMMARY_PREFIX.length()), room);
                used = fixed + summary.length();
            }
            lines.add(0, line);
            carried.add(0, candidates.get(i));
            used += line.length() + 1;
        }

        StringBuilder sb = new StringBuilder(header);
        if (!summary.isEmpty()) sb.append(summary).append("\n---\n");
        for (String line : lines) sb.append(line).append('\n');
        sb.append(footer);
        return new HandoffBlock(sb.toString(), List.copyOf(carried), through);
    }

    /** {@code [hh:mm canal] Rol: texto (mostró …) (adjunto …)}; empty when the turn shows nothing. */
    static String line(Turn t, String defaultChannel) {
        String text = t.content() == null ? "" : t.content().strip().replace("\n", " ");
        List<String> notes = new ArrayList<>();
        Map<String, Object> meta = t.metadata() == null ? Map.of() : t.metadata();
        if (meta.get("uiHints") instanceof List<?> hints) {
            for (Object h : hints) {
                if (h instanceof Map<?, ?> m) notes.add(hintNote(m));
            }
        }
        if (meta.get("attachments") instanceof List<?> attachments) {
            for (Object a : attachments) {
                if (a instanceof Map<?, ?> m) notes.add(attachmentNote(m));
            }
        }
        if (text.isEmpty() && notes.isEmpty()) return "";
        String channel = meta.get("channel") instanceof String c && !c.isBlank() ? c : defaultChannel;
        String when = t.createdAt() == null ? "--:--" : HOUR.format(t.createdAt());
        StringBuilder sb = new StringBuilder("[").append(when).append(' ').append(channel).append("] ")
                .append(t.role() == Turn.Role.USER ? "Usuario: " : "Asistente: ").append(text);
        for (String note : notes) {
            if (sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
            sb.append(note);
        }
        return sb.toString().replace('<', '‹').replace('>', '›');
    }

    private static String hintNote(Map<?, ?> hint) {
        String kind = String.valueOf(hint.get("kind"));
        Map<?, ?> payload = hint.get("payload") instanceof Map<?, ?> p ? p : Map.of();
        return switch (kind) {
            case "confirm" -> "(mostró una confirmación: «" + firstOf(payload, "summary", "text") + "»)";
            case "plan" -> "(mostró un plan: «" + firstOf(payload, "text", "summary") + "»)";
            case "open_screen" -> "(abrió la pantalla " + firstOf(payload, "screenId", "route") + ")";
            case "attachment" -> "(adjuntó " + firstOf(payload, "filename", "name", "assetId") + ")";
            case "secret_prompt" -> "(pidió un código)";
            default -> "(mostró " + kind + ")";
        };
    }

    private static String attachmentNote(Map<?, ?> attachment) {
        String name = firstOf(attachment, "filename", "name", "id");
        String kind = firstOf(attachment, "kind", "contentType");
        return "(adjunto: " + name + (kind.isEmpty() ? "" : ", " + kind.toLowerCase(Locale.ROOT)) + ")";
    }

    private static String firstOf(Map<?, ?> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v).replace("\n", " ");
        }
        return "";
    }

    private static String tailOf(String s, int room) {
        if (s.length() <= room) return s;
        return "…" + s.substring(s.length() - Math.max(0, room - 1));
    }

    /** How the instruction names the channel the person comes from. */
    public static String humanChannel(String channel) {
        if (channel == null) return "el otro canal";
        return switch (channel.toLowerCase(Locale.ROOT)) {
            case "whatsapp" -> "WhatsApp";
            case "admin-app", "app", "web" -> "la app";
            case "chat" -> "el chat";
            case "sms" -> "SMS";
            case "console" -> "la consola";
            default -> "el canal «" + channel + "»";
        };
    }
}
