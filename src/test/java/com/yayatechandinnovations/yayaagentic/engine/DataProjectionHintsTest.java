package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.tool.HttpToolSpec.ResponseProjection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §D4 extraction: a tool's declarative {@link ResponseProjection} config
 * projects an OK result into {@code data_table} / {@code data_card} payloads,
 * fail-soft when a path is absent or ill-typed.
 */
class DataProjectionHintsTest {

    // A representative tool result: a summary + a rows/columns table.
    private static Object result() {
        return Map.of(
                "title", "Ventas de hoy",
                "columns", List.of(
                        Map.of("key", "lottery", "label", "Lotería", "type", "text"),
                        Map.of("key", "sold", "label", "Vendido", "type", "money")),
                "rows", List.of(
                        Map.of("lottery", "Nacional", "sold", 1500),
                        Map.of("lottery", "Real", "sold", 900)),
                "totals", Map.of("sold", 2400),
                "netToday", 2400,
                "ticketCount", 37);
    }

    @Test
    void dataTableExtractsColumnsRowsTitleAndTotals() {
        ResponseProjection proj = new ResponseProjection("$", Map.of(), null,
                Map.of("titlePath", "$.title",
                        "columnsPath", "$.columns",
                        "rowsPath", "$.rows",
                        "totalsPath", "$.totals"),
                null);

        Map<String, Object> payload = DataProjectionHints.dataTable(proj, result());

        assertThat(payload).isNotNull();
        assertThat(payload.get("title")).isEqualTo("Ventas de hoy");
        assertThat((List<?>) payload.get("columns")).hasSize(2);
        assertThat((List<?>) payload.get("rows")).hasSize(2);
        assertThat(payload.get("totals")).isEqualTo(Map.of("sold", 2400));
    }

    @Test
    void dataTableFailsSoftWhenPathsMissingOrNotList() {
        // columnsPath resolves to a scalar → not-a-list → no hint.
        ResponseProjection bad = new ResponseProjection("$", Map.of(), null,
                Map.of("columnsPath", "$.netToday", "rowsPath", "$.rows"), null);
        assertThat(DataProjectionHints.dataTable(bad, result())).isNull();

        // Missing rowsPath → no hint.
        ResponseProjection missing = new ResponseProjection("$", Map.of(), null,
                Map.of("columnsPath", "$.columns"), null);
        assertThat(DataProjectionHints.dataTable(missing, result())).isNull();

        // No table config at all → no hint.
        ResponseProjection none = new ResponseProjection("$", Map.of());
        assertThat(DataProjectionHints.dataTable(none, result())).isNull();
    }

    @Test
    void dataCardBuildsRowsAndSkipsMissingFields() {
        ResponseProjection proj = new ResponseProjection("$", Map.of(), null,
                Map.of("titlePath", "$.title"),
                List.of(
                        Map.of("label", "Neto hoy", "path", "$.netToday", "type", "money"),
                        Map.of("label", "Tickets", "path", "$.ticketCount", "type", "count"),
                        // This one misses — must be skipped, not emitted as a null row.
                        Map.of("label", "Ausente", "path", "$.doesNotExist", "type", "text")));

        Map<String, Object> payload = DataProjectionHints.dataCard(proj, result());

        assertThat(payload).isNotNull();
        assertThat(payload.get("title")).isEqualTo("Ventas de hoy");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.get("rows");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("label", "Neto hoy")
                .containsEntry("value", 2400).containsEntry("type", "money");
        assertThat(rows.get(1)).containsEntry("label", "Tickets")
                .containsEntry("value", 37).containsEntry("type", "count");
    }

    @Test
    void dataCardDefaultsTypeToTextAndFailsSoftWhenNoRowsResolve() {
        // type omitted → defaults to "text".
        ResponseProjection typed = new ResponseProjection("$", Map.of(), null, null,
                List.of(Map.of("label", "Neto", "path", "$.netToday")));
        Map<String, Object> payload = DataProjectionHints.dataCard(typed, result());
        assertThat(payload).isNotNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.get("rows");
        assertThat(rows.get(0)).containsEntry("type", "text");
        assertThat(payload).doesNotContainKey("title");

        // Every field misses → no card at all.
        ResponseProjection allMiss = new ResponseProjection("$", Map.of(), null, null,
                List.of(Map.of("label", "x", "path", "$.nope")));
        assertThat(DataProjectionHints.dataCard(allMiss, result())).isNull();

        // No card config → no card.
        assertThat(DataProjectionHints.dataCard(new ResponseProjection("$", Map.of()), result())).isNull();
    }
}
