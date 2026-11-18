package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.QuickReplies;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Assistant-channels design §2.6: the options last offered, and how a tap or a number names one. */
class OfferedOptionsStoreTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("t1");
    private static final SenderId SENDER = new SenderId("s1");
    private static final String PHONE = "+18095550001";

    private final OfferedOptionsStore store = OfferedOptionsStore.inMemory(new ObjectMapper());

    private static OfferedOptionsStore.Offered offered(int n, boolean numbered) {
        List<QuickReplies.Option> options = new ArrayList<>();
        for (int i = 1; i <= n; i++) options.add(new QuickReplies.Option("o" + i, "Opción " + i, "Elijo la " + i));
        Map<String, Object> payload = QuickReplies.payload(QuickReplies.SOURCE_RULE, options);
        return new OfferedOptionsStore.Offered("sess-1", options, numbered, 0, payload);
    }

    @Test
    void remembered_options_come_back_with_their_send_and_page_state() {
        store.remember(TENANT, SENDER, PHONE, offered(4, true));
        Optional<OfferedOptionsStore.Offered> back = store.recall(TENANT, SENDER, PHONE);
        assertThat(back).isPresent();
        assertThat(back.get().sessionId()).isEqualTo("sess-1");
        assertThat(back.get().numbered()).isTrue();
        assertThat(back.get().offset()).isZero();
        assertThat(back.get().options()).extracting(QuickReplies.Option::send).contains("Elijo la 3");
        assertThat(store.recall(TENANT, SENDER, "+10000000000")).isEmpty();
    }

    @Test
    void an_empty_set_or_a_forget_clears_the_record() {
        store.remember(TENANT, SENDER, PHONE, offered(2, false));
        store.remember(TENANT, SENDER, PHONE, new OfferedOptionsStore.Offered("s", List.of(), false, 0, Map.of()));
        assertThat(store.recall(TENANT, SENDER, PHONE)).isEmpty();
        store.remember(TENANT, SENDER, PHONE, offered(2, false));
        store.forget(TENANT, SENDER, PHONE);
        assertThat(store.recall(TENANT, SENDER, PHONE)).isEmpty();
    }

    @Test
    void an_id_names_its_option_with_or_without_the_prefix() {
        OfferedOptionsStore.Offered o = offered(3, false);
        assertThat(OfferedOptionsStore.byId(o, "opt:o2").map(QuickReplies.Option::send)).contains("Elijo la 2");
        assertThat(OfferedOptionsStore.byId(o, "o3").map(QuickReplies.Option::send)).contains("Elijo la 3");
        assertThat(OfferedOptionsStore.byId(o, "opt:__more__")).contains(OfferedOptionsStore.MORE);
        assertThat(OfferedOptionsStore.byId(o, "opt:zz")).isEmpty();
        assertThat(OfferedOptionsStore.byId(null, "opt:o1")).isEmpty();
    }

    @Test
    void a_bare_number_names_the_row_on_the_page_shown_only_after_a_numbered_list() {
        OfferedOptionsStore.Offered numbered = offered(12, true);
        assertThat(OfferedOptionsStore.byNumber(numbered, "2").map(QuickReplies.Option::send)).contains("Elijo la 2");
        assertThat(OfferedOptionsStore.byNumber(numbered, " 9. ").map(QuickReplies.Option::send)).contains("Elijo la 9");
        // Row 10 of a 12-option list is «Más…».
        assertThat(OfferedOptionsStore.byNumber(numbered, "10")).contains(OfferedOptionsStore.MORE);
        assertThat(OfferedOptionsStore.byNumber(numbered, "11")).isEmpty();
        assertThat(OfferedOptionsStore.byNumber(numbered, "0")).isEmpty();
        assertThat(OfferedOptionsStore.byNumber(numbered, "dos")).isEmpty();
        assertThat(OfferedOptionsStore.byNumber(numbered, "2 tickets")).isEmpty();
        // After native buttons a digit is just a digit.
        assertThat(OfferedOptionsStore.byNumber(offered(3, false), "2")).isEmpty();
        // The second page numbers from its own first row.
        OfferedOptionsStore.Offered page2 = numbered.atOffset(9);
        assertThat(OfferedOptionsStore.byNumber(page2, "1").map(QuickReplies.Option::send)).contains("Elijo la 10");
        assertThat(page2.page()).hasSize(3);
        assertThat(page2.hasMore()).isFalse();
        assertThat(numbered.hasMore()).isTrue();
        // Ten options fit one page: no «Más…».
        assertThat(offered(10, true).page()).hasSize(10).doesNotContain(OfferedOptionsStore.MORE);
    }

    @Test
    void list_picker_variables_follow_the_row_layout() {
        Map<String, String> vars = InSessionContents.listPickerVariables("Cuerpo", "Elegir", List.of(
                new String[]{"Anular", "opt:undo", "Anular el ticket 4411"},
                new String[]{"Pagar", "opt:pay", "Pagar"}));
        assertThat(vars).containsEntry("1", "Cuerpo").containsEntry("2", "Elegir")
                .containsEntry("3", "Anular").containsEntry("4", "opt:undo").containsEntry("5", "Anular el ticket 4411")
                .containsEntry("6", "Pagar").containsEntry("7", "opt:pay").containsEntry("8", "Pagar");
    }
}
