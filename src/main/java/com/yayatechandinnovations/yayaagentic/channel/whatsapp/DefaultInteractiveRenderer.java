package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.channel.InteractivePayload;
import com.yayatechandinnovations.yayaagentic.channel.InteractiveRenderer;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Default {@link InteractiveRenderer} for WhatsApp via Twilio. Implements
 * the §10.2 count-based decision table:
 *
 * <table>
 *   <caption>UiHint option count → InteractivePayload variant</caption>
 *   <tr><th>Options</th><th>Surface</th><th>Notes</th></tr>
 *   <tr><td>0</td><td>PlainText</td><td>nothing to render</td></tr>
 *   <tr><td>1–3</td><td>Buttons</td><td>WhatsApp Quick Reply Buttons</td></tr>
 *   <tr><td>4–10</td><td>List</td><td>one section</td></tr>
 *   <tr><td>&gt;10</td><td>List (paginated)</td><td>first 9 options + "More…"</td></tr>
 * </table>
 *
 * <p>A label exceeding 20 chars also forces a downgrade Buttons → List, per
 * WhatsApp's button-title cap.
 */
@Component
public class DefaultInteractiveRenderer implements InteractiveRenderer {

    /** WhatsApp's title cap on Quick Reply Buttons. */
    public static final int BUTTON_TITLE_MAX = 20;

    /** WhatsApp's footer cap. */
    public static final int FOOTER_MAX = 60;

    /** Per §10.2 the 10-overflow rule reserves the 10th row as "More…". */
    public static final int FIRST_PAGE_ROWS = 9;

    public static final String MORE_ROW_ID = "__more__";

    /** Button payload prefixes the inbound worker maps back to selections (§6.5). */
    public static final String CONFIRM_PREFIX = "confirm:";
    public static final String CANCEL_PREFIX = "cancel:";
    public static final String ACTION_PREFIX = "act:";

    @Override
    public InteractivePayload render(String bodyText, TurnEvent.UiHint hint, RenderContext ctx) {
        String body = bodyText == null ? "" : bodyText.trim();
        String footer = truncate(ctx == null ? null : ctx.citationFootnote(), FOOTER_MAX);

        if (hint != null && "confirm".equals(hint.kind())) {
            return confirmButtons(body, hint.payload(), footer);
        }
        if (hint != null && "plan".equals(hint.kind())) {
            return planButtons(body, hint.payload(), footer);
        }
        if (hint == null || !"quick_replies".equals(hint.kind())) {
            return new InteractivePayload.PlainText(body);
        }
        List<OptionRow> options = readOptions(hint.payload());
        if (options.isEmpty()) {
            return new InteractivePayload.PlainText(body);
        }

        boolean anyOverLength = options.stream()
                .anyMatch(o -> o.label().length() > BUTTON_TITLE_MAX);

        if (options.size() <= 3 && !anyOverLength) {
            List<InteractivePayload.Button> buttons = new ArrayList<>();
            for (OptionRow o : options) {
                buttons.add(new InteractivePayload.Button(o.id(), o.label()));
            }
            return new InteractivePayload.Buttons(body, buttons, footer);
        }

        String header = readHeader(hint.payload());
        String buttonText = "Choose";
        if (options.size() <= 10) {
            List<InteractivePayload.Row> rows = options.stream()
                    .map(o -> new InteractivePayload.Row(o.id(), truncate(o.label(), 24),
                            o.description()))
                    .toList();
            return new InteractivePayload.ListMessage(body, header, buttonText,
                    List.of(new InteractivePayload.Section(
                            truncate(headerOr(header, "Choose"), 24), rows)),
                    footer);
        }

        // > 10 options — first page is 9 real rows + a "More…" sentinel
        List<InteractivePayload.Row> firstPage = new ArrayList<>();
        for (int i = 0; i < FIRST_PAGE_ROWS && i < options.size(); i++) {
            OptionRow o = options.get(i);
            firstPage.add(new InteractivePayload.Row(o.id(), truncate(o.label(), 24),
                    o.description()));
        }
        firstPage.add(new InteractivePayload.Row(MORE_ROW_ID, "More…", "Show more options"));
        return new InteractivePayload.ListMessage(body, header, buttonText,
                List.of(new InteractivePayload.Section(
                        truncate(headerOr(header, "Choose"), 24), firstPage)),
                footer);
    }

    /**
     * The engine's confirm frame as two buttons whose payloads name the frame
     * ({@code confirm:<callId>} / {@code cancel:<callId>}), so the tap resolves
     * exactly that frame (whatsapp-ops-integration-design.md §6.5, GAP-C4). The
     * multi-item checkbox variant cannot prune on WhatsApp: Confirmar is all.
     */
    private static InteractivePayload confirmButtons(String body, Map<String, Object> payload, String footer) {
        Object callId = payload == null ? null : payload.get("callId");
        if (callId == null) return new InteractivePayload.PlainText(body);
        String confirm = truncate(stringOr(payload.get("confirmLabel"), "Confirmar"), BUTTON_TITLE_MAX);
        String cancel = truncate(stringOr(payload.get("cancelLabel"), "Cancelar"), BUTTON_TITLE_MAX);
        String text = body.isEmpty() ? stringOr(payload.get("summary"), "") : body;
        return new InteractivePayload.Buttons(text, List.of(
                new InteractivePayload.Button(CONFIRM_PREFIX + callId, confirm),
                new InteractivePayload.Button(CANCEL_PREFIX + callId, cancel)), footer);
    }

    /**
     * A plan (admin-panel-sessions.md §16) like a confirm frame: the list of
     * steps as the body and two buttons, «Hacer todo» / «Cancelar», that name
     * the plan. The buttons are all or nothing; a step is left out by typing
     * the numbers of the ones wanted ({@code PlanAnswers}).
     */
    private static InteractivePayload planButtons(String body, Map<String, Object> payload, String footer) {
        Object callId = payload == null ? null : payload.get("callId");
        if (callId == null) return new InteractivePayload.PlainText(body);
        String confirm = truncate(stringOr(payload.get("confirmLabel"), "Hacer todo"), BUTTON_TITLE_MAX);
        String cancel = truncate(stringOr(payload.get("cancelLabel"), "Cancelar"), BUTTON_TITLE_MAX);
        String text = body.isEmpty() ? stringOr(payload.get("text"), "") : body;
        return new InteractivePayload.Buttons(text, List.of(
                new InteractivePayload.Button(CONFIRM_PREFIX + callId, confirm),
                new InteractivePayload.Button(CANCEL_PREFIX + callId, cancel)), footer);
    }

    /** Extract paginated options for the "More…" follow-up — returns the
     *  subsequent page starting after the given offset, using the same
     *  per-page reservation rules. */
    public InteractivePayload renderPage(String bodyText, TurnEvent.UiHint hint,
                                         int offset, RenderContext ctx) {
        String body = bodyText == null ? "" : bodyText.trim();
        String footer = truncate(ctx == null ? null : ctx.citationFootnote(), FOOTER_MAX);
        List<OptionRow> options = readOptions(hint.payload());
        if (offset < 0 || offset >= options.size()) {
            return new InteractivePayload.PlainText(body);
        }
        int remaining = options.size() - offset;
        boolean lastPage = remaining <= 10;
        int take = lastPage ? remaining : FIRST_PAGE_ROWS;
        List<InteractivePayload.Row> rows = new ArrayList<>();
        for (int i = 0; i < take; i++) {
            OptionRow o = options.get(offset + i);
            rows.add(new InteractivePayload.Row(o.id(), truncate(o.label(), 24), o.description()));
        }
        if (!lastPage) {
            rows.add(new InteractivePayload.Row(MORE_ROW_ID, "More…", "Show more options"));
        }
        return new InteractivePayload.ListMessage(body,
                readHeader(hint.payload()), "Choose",
                List.of(new InteractivePayload.Section("Choose", rows)),
                footer);
    }

    @SuppressWarnings("unchecked")
    private static List<OptionRow> readOptions(Map<String, Object> payload) {
        if (payload == null) return List.of();
        Object opts = payload.get("options");
        if (!(opts instanceof List<?> list)) return List.of();
        List<OptionRow> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String id = stringOr(m.get("id"), null);
            String label = stringOr(m.get("label"), null);
            if (id == null || label == null) continue;
            String description = stringOr(m.get("description"), null);
            out.add(new OptionRow(id, label, description));
        }
        return out;
    }

    private static String readHeader(Map<String, Object> payload) {
        if (payload == null) return null;
        Object h = payload.get("headerText");
        return h == null ? null : h.toString();
    }

    private static String headerOr(String h, String fallback) {
        return h == null || h.isBlank() ? fallback : h;
    }

    private static String stringOr(Object v, String fallback) {
        return v == null ? fallback : v.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private record OptionRow(String id, String label, String description) {}
}
