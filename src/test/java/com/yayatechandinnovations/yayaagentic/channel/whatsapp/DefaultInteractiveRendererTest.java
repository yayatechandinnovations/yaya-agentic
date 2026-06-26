package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.channel.ChannelKind;
import com.yayatechandinnovations.yayaagentic.channel.InteractivePayload;
import com.yayatechandinnovations.yayaagentic.channel.InteractiveRenderer;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Truth-table coverage of the §10.2 count-based decision table for
 * Twilio's interactive surfaces.
 */
class DefaultInteractiveRendererTest {

    private final DefaultInteractiveRenderer renderer = new DefaultInteractiveRenderer();
    private final InteractiveRenderer.RenderContext ctx =
            new InteractiveRenderer.RenderContext(new ChannelKind.WhatsApp(), null);

    @Test
    void no_hint_renders_plain_text() {
        var payload = renderer.render("Hello", null, ctx);
        assertThat(payload).isInstanceOf(InteractivePayload.PlainText.class);
    }

    @Test
    void zero_options_renders_plain_text() {
        var hint = new TurnEvent.UiHint("quick_replies", Map.of("options", List.of()));
        var payload = renderer.render("Body", hint, ctx);
        assertThat(payload).isInstanceOf(InteractivePayload.PlainText.class);
    }

    @Test
    void three_options_render_buttons() {
        var payload = renderer.render("Pick one", hintWith(3), ctx);
        assertThat(payload).isInstanceOf(InteractivePayload.Buttons.class);
        var buttons = (InteractivePayload.Buttons) payload;
        assertThat(buttons.buttons()).hasSize(3);
    }

    @Test
    void four_options_render_list() {
        var payload = renderer.render("Pick one", hintWith(4), ctx);
        assertThat(payload).isInstanceOf(InteractivePayload.ListMessage.class);
        var list = (InteractivePayload.ListMessage) payload;
        assertThat(list.sections()).hasSize(1);
        assertThat(list.sections().get(0).rows()).hasSize(4);
    }

    @Test
    void ten_options_fit_in_one_list_section() {
        var payload = renderer.render("Pick one", hintWith(10), ctx);
        var list = (InteractivePayload.ListMessage) payload;
        assertThat(list.sections().get(0).rows()).hasSize(10);
        assertThat(list.sections().get(0).rows())
                .noneMatch(r -> r.id().equals(DefaultInteractiveRenderer.MORE_ROW_ID));
    }

    @Test
    void eleven_options_paginates_with_more_row() {
        var payload = renderer.render("Pick one", hintWith(11), ctx);
        var list = (InteractivePayload.ListMessage) payload;
        var rows = list.sections().get(0).rows();
        assertThat(rows).hasSize(10);    // 9 real + 1 "More…"
        assertThat(rows.get(rows.size() - 1).id())
                .isEqualTo(DefaultInteractiveRenderer.MORE_ROW_ID);
    }

    @Test
    void long_label_downgrades_buttons_to_list() {
        var options = new ArrayList<Map<String, Object>>();
        options.add(opt("a", "This label is far too long for a button surface"));
        options.add(opt("b", "Short"));
        var hint = new TurnEvent.UiHint("quick_replies", Map.of("options", options));
        var payload = renderer.render("Body", hint, ctx);
        assertThat(payload).isInstanceOf(InteractivePayload.ListMessage.class);
    }

    @Test
    void second_page_renders_via_render_page() {
        TurnEvent.UiHint hint = hintWith(15);
        var page2 = renderer.renderPage("Body", hint, 9, ctx);
        var list = (InteractivePayload.ListMessage) page2;
        // Offset 9 with 15 total → 6 remaining options. Fits in one page (≤10),
        // so no "More…" row should appear.
        assertThat(list.sections().get(0).rows()).hasSize(6);
        assertThat(list.sections().get(0).rows())
                .noneMatch(r -> r.id().equals(DefaultInteractiveRenderer.MORE_ROW_ID));
    }

    /* ---------------------------- helpers --------------------------------- */

    private TurnEvent.UiHint hintWith(int count) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            options.add(opt("id_" + i, "Option " + i));
        }
        return new TurnEvent.UiHint("quick_replies", Map.of("options", options));
    }

    private static Map<String, Object> opt(String id, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        return m;
    }
}
