package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.engine.confirm.ConfirmPrompts;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The one {@code quick_replies} payload every producer emits and every
 * renderer reads (assistant-channels design §2.2):
 *
 * <pre>
 * { "options": [ { "id": "undo", "label": "Anular", "send": "Anular el ticket 4411", "style": "primary" } ],
 *   "source": "rule|start|ask|frame|suggester" }
 * </pre>
 *
 * {@code send} defaults to {@code label}; {@code id} to a slug of the label;
 * {@code style} is optional. The legacy shapes — {@code items:[String]},
 * {@code actions:[{label,send}]} and {@code options} without {@code send} —
 * are still accepted on the way in ({@link #normalise}) and by consumers
 * ({@link #read}), so a parked or replayed hint keeps working. A chip carries
 * plain text, never a tool or arguments (non-negotiable #8).
 */
public final class QuickReplies {

    public static final String KIND = "quick_replies";

    public static final String SOURCE_RULE = "rule";
    public static final String SOURCE_START = "start";
    public static final String SOURCE_ASK = "ask";
    public static final String SOURCE_FRAME = "frame";
    public static final String SOURCE_SUGGESTER = "suggester";

    /** WhatsApp's quick-reply title cap, also the suggester's label limit. */
    public static final int LABEL_MAX = 20;

    private static final int ID_MAX = 40;

    private QuickReplies() {}

    /** One tappable option. {@code style} is {@code primary|danger|neutral} or null. */
    public record Option(String id, String label, String send, String style) {
        public Option {
            label = label == null ? "" : label.strip();
            send = send == null || send.isBlank() ? label : send.strip();
            id = id == null || id.isBlank() ? slug(label) : id.strip();
            style = style == null || style.isBlank() ? null : style.strip();
        }

        public Option(String id, String label, String send) {
            this(id, label, send, null);
        }

        public static Option of(String label) {
            return new Option(null, label, null, null);
        }

        public static Option of(String label, String send) {
            return new Option(null, label, send, null);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("label", label);
            m.put("send", send);
            if (style != null) m.put("style", style);
            return m;
        }
    }

    // ---- producers ---------------------------------------------------------

    /** The canonical hint. Empty options give an empty hint (consumers draw nothing). */
    public static TurnEvent.UiHint hint(String source, List<Option> options) {
        return new TurnEvent.UiHint(KIND, payload(source, options));
    }

    public static Map<String, Object> payload(String source, List<Option> options) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (options != null) {
            for (Option o : options) {
                if (o == null || o.label().isEmpty()) continue;
                String id = o.id();
                int n = 2;
                while (!ids.add(id)) id = o.id() + "_" + n++;
                rows.add(new Option(id, o.label(), o.send(), o.style()).toMap());
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("options", rows);
        payload.put("source", source == null || source.isBlank() ? SOURCE_RULE : source);
        return payload;
    }

    /** Label-only chips (a capability's {@code followUpHints}, a start flow's replies). */
    public static TurnEvent.UiHint fromLabels(String source, List<String> labels) {
        List<Option> options = new ArrayList<>();
        if (labels != null) for (String l : labels) if (l != null && !l.isBlank()) options.add(Option.of(l));
        return hint(source, options);
    }

    /** Suggestion-rule chips: what is shown and what tapping it sends. */
    public static TurnEvent.UiHint fromChips(String source, List<SuggestionRuleEvaluator.Chip> chips) {
        List<Option> options = new ArrayList<>();
        if (chips != null) for (var c : chips) options.add(Option.of(c.label(), c.send()));
        return hint(source, options);
    }

    /** The localised «Sí» / «No» pair. */
    public static List<Option> yesNo(String language) {
        boolean es = ConfirmPrompts.isSpanish(language);
        return List.of(new Option("yes", es ? "Sí" : "Yes", null, "primary"),
                new Option("no", es ? "No" : "No", null, "neutral"));
    }

    // ---- normalisation -----------------------------------------------------

    /**
     * Any producer's payload as the canonical one. {@code options} wins when
     * present; otherwise {@code actions} ({@code {label,send}}), otherwise
     * {@code items} (labels). A {@code source} already set is kept.
     */
    public static Map<String, Object> normalise(Map<String, Object> payload, String defaultSource) {
        String source = payload == null || !(payload.get("source") instanceof String s) || s.isBlank()
                ? defaultSource : s;
        Map<String, Object> out = payload(source, read(payload));
        if (payload != null) {
            // Renderer extras (a list header, a footer) ride along untouched.
            payload.forEach((k, v) -> {
                if (!"options".equals(k) && !"actions".equals(k) && !"items".equals(k) && !"source".equals(k)) {
                    out.put(k, v);
                }
            });
        }
        return out;
    }

    public static TurnEvent.UiHint normalise(TurnEvent.UiHint hint, String defaultSource) {
        if (hint == null || !KIND.equals(hint.kind())) return hint;
        return new TurnEvent.UiHint(KIND, normalise(hint.payload(), defaultSource));
    }

    /** The options of any shape, canonical or legacy. Empty when there are none. */
    public static List<Option> read(Map<String, Object> payload) {
        if (payload == null) return List.of();
        List<Option> out = new ArrayList<>();
        if (payload.get("options") instanceof List<?> options && !options.isEmpty()) {
            for (Object item : options) {
                if (item instanceof Map<?, ?> m) {
                    String label = str(m.get("label"));
                    if (label == null || label.isBlank()) label = str(m.get("send"));
                    if (label == null || label.isBlank()) continue;
                    out.add(new Option(str(m.get("id")), label, str(m.get("send")), str(m.get("style"))));
                } else if (item instanceof String s && !s.isBlank()) {
                    out.add(Option.of(s));
                }
            }
            return out;
        }
        if (payload.get("actions") instanceof List<?> actions && !actions.isEmpty()) {
            for (Object item : actions) {
                if (!(item instanceof Map<?, ?> m)) continue;
                String label = str(m.get("label"));
                String send = str(m.get("send"));
                if ((label == null || label.isBlank()) && (send == null || send.isBlank())) continue;
                out.add(new Option(str(m.get("id")), label == null || label.isBlank() ? send : label, send,
                        str(m.get("style"))));
            }
            return out;
        }
        if (payload.get("items") instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof String s && !s.isBlank()) out.add(Option.of(s));
                else if (item instanceof Map<?, ?> m && str(m.get("label")) != null) {
                    out.add(new Option(str(m.get("id")), str(m.get("label")), str(m.get("send")), null));
                }
            }
        }
        return out;
    }

    public static String sourceOf(Map<String, Object> payload) {
        return payload != null && payload.get("source") instanceof String s ? s : null;
    }

    /** A stable id from a label: ascii, lower case, {@code _} between words. */
    public static String slug(String label) {
        if (label == null) return "opt";
        String ascii = Normalizer.normalize(label, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (ascii.isEmpty()) ascii = "opt";
        return ascii.length() <= ID_MAX ? ascii : ascii.substring(0, ID_MAX).replaceAll("_+$", "");
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
