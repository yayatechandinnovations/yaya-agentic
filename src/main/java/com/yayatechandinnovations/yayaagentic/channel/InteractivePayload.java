package com.yayatechandinnovations.yayaagentic.channel;

import java.util.List;

/**
 * Renderer-output sealed type used by {@link InteractiveRenderer}. The
 * channel's outbound dispatcher consumes the variant: {@link PlainText} maps
 * to a regular SMS-style message; {@link Buttons} and {@link ListMessage}
 * unwrap into Twilio's WhatsApp interactive payload (Quick Reply Buttons +
 * List Messages respectively).
 *
 * <p>See {@code whatsapp-channel-design.md} §10.2 + appendix.
 */
public sealed interface InteractivePayload
        permits InteractivePayload.PlainText,
                InteractivePayload.Buttons,
                InteractivePayload.ListMessage {

    record PlainText(String body) implements InteractivePayload {}

    record Buttons(String body, List<Button> buttons, String footer)
            implements InteractivePayload {
        public Buttons {
            buttons = buttons == null ? List.of() : List.copyOf(buttons);
        }
    }

    record ListMessage(String body, String header, String buttonText,
                       List<Section> sections, String footer)
            implements InteractivePayload {
        public ListMessage {
            sections = sections == null ? List.of() : List.copyOf(sections);
        }
    }

    /** WhatsApp Quick Reply Button. Twilio enforces {@code title} ≤ 20
     *  chars; the renderer downgrades to {@link ListMessage} when a label
     *  exceeds that. */
    record Button(String id, String title) {
        public Button {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
            if (title == null || title.isBlank()) throw new IllegalArgumentException("title required");
        }
    }

    record Section(String title, List<Row> rows) {
        public Section {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    record Row(String id, String title, String description) {
        public Row {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
            if (title == null || title.isBlank()) throw new IllegalArgumentException("title required");
        }
    }
}
