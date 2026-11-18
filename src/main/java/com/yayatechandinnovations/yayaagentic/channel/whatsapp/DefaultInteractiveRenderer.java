package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.channel.InteractivePayload;
import com.yayatechandinnovations.yayaagentic.channel.InteractiveRenderer;
import com.yayatechandinnovations.yayaagentic.engine.QuickReplies;
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
 *   <tr><td>1–3</td><td>Buttons</td><td>WhatsApp Quick Reply Buttons, payload {@code opt:<id>}</td></tr>
 *   <tr><td>4–10</td><td>List</td><td>one section, native list picker</td></tr>
 *   <tr><td>&gt;10</td><td>List (paginated)</td><td>first 9 options + «Más…»</td></tr>
 * </table>
 *
 * <p>A label exceeding 20 chars also forces a downgrade Buttons → List, per
 * WhatsApp's button-title cap. The hint is read in the canonical
 * {@code quick_replies} shape and in the legacy ones ({@link QuickReplies}).
 * Copy is Spanish, like the rest of the WhatsApp leg.
 */
@Component
public class DefaultInteractiveRenderer implements InteractiveRenderer {

    /** WhatsApp's title cap on Quick Reply Buttons. */
    public static final int BUTTON_TITLE_MAX = 20;

    /** WhatsApp's caps on a list row. */
    public static final int ROW_TITLE_MAX = 24;
    public static final int ROW_DESCRIPTION_MAX = 72;

    /** WhatsApp's footer cap. */
    public static final int FOOTER_MAX = 60;

    /** Per §10.2 the 10-overflow rule reserves the 10th row as «Más…». */
    public static final int FIRST_PAGE_ROWS = 9;

    public static final String MORE_ROW_ID = "__more__";
    public static final String MORE_LABEL = "Más…";
    public static final String MORE_DESCRIPTION = "Ver más opciones";
    public static final String CHOOSE_LABEL = "Elegir";

    /** Button payload prefixes the inbound worker maps back to selections (§6.5, §2.6). */
    public static final String CONFIRM_PREFIX = "confirm:";
    public static final String CANCEL_PREFIX = "cancel:";
    public static final String ACTION_PREFIX = "act:";
    public static final String OPTION_PREFIX = "opt:";

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
        if (hint == null || !QuickReplies.KIND.equals(hint.kind())) {
            return new InteractivePayload.PlainText(body);
        }
        List<QuickReplies.Option> options = QuickReplies.read(hint.payload());
        if (options.isEmpty()) {
            return new InteractivePayload.PlainText(body);
        }

        boolean anyOverLength = options.stream()
                .anyMatch(o -> o.label().length() > BUTTON_TITLE_MAX);

        if (options.size() <= 3 && !anyOverLength) {
            List<InteractivePayload.Button> buttons = new ArrayList<>();
            for (QuickReplies.Option o : options) {
                buttons.add(new InteractivePayload.Button(OPTION_PREFIX + o.id(), o.label()));
            }
            return new InteractivePayload.Buttons(body, buttons, footer);
        }

        return page(body, hint.payload(), options, 0, footer);
    }

    /**
     * The page starting at {@code offset} for the «Más…» follow-up (§10.3):
     * the same per-page reservation rules as the first page.
     */
    public InteractivePayload renderPage(String bodyText, TurnEvent.UiHint hint,
                                         int offset, RenderContext ctx) {
        String body = bodyText == null ? "" : bodyText.trim();
        String footer = truncate(ctx == null ? null : ctx.citationFootnote(), FOOTER_MAX);
        List<QuickReplies.Option> options = hint == null ? List.of() : QuickReplies.read(hint.payload());
        if (offset < 0 || offset >= options.size()) {
            return new InteractivePayload.PlainText(body);
        }
        return page(body, hint.payload(), options, offset, footer);
    }

    private static InteractivePayload page(String body, Map<String, Object> payload,
                                           List<QuickReplies.Option> options, int offset, String footer) {
        int remaining = options.size() - offset;
        boolean lastPage = remaining <= FIRST_PAGE_ROWS + 1;
        int take = lastPage ? remaining : FIRST_PAGE_ROWS;
        List<InteractivePayload.Row> rows = new ArrayList<>();
        for (int i = 0; i < take; i++) {
            rows.add(row(options.get(offset + i)));
        }
        if (!lastPage) {
            rows.add(new InteractivePayload.Row(OPTION_PREFIX + MORE_ROW_ID, MORE_LABEL, MORE_DESCRIPTION));
        }
        String header = readHeader(payload);
        return new InteractivePayload.ListMessage(body, header, CHOOSE_LABEL,
                List.of(new InteractivePayload.Section(
                        truncate(headerOr(header, CHOOSE_LABEL), ROW_TITLE_MAX), rows)),
                footer);
    }

    /** A row shows the label; when tapping it sends something else, that text is the description. */
    private static InteractivePayload.Row row(QuickReplies.Option o) {
        String description = o.send() != null && !o.send().equals(o.label())
                ? truncate(o.send(), ROW_DESCRIPTION_MAX) : null;
        return new InteractivePayload.Row(OPTION_PREFIX + o.id(), truncate(o.label(), ROW_TITLE_MAX), description);
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
}
