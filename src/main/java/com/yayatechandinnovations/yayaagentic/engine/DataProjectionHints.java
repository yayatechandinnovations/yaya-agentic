package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.tool.HttpToolSpec.ResponseProjection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a tool's declarative {@link ResponseProjection} data config into
 * {@code data_table} / {@code data_card} UiHint payloads (owner-cockpit v3
 * §D4). Pure and config-driven — never LLM-driven — and fully fail-soft: a
 * missing or ill-typed path yields no hint, never an exception. Navigation
 * reuses {@link SuggestionRuleEvaluator#navigate} so table/card projections
 * speak the same {@code $.path} dialect the rest of the engine does.
 */
final class DataProjectionHints {

    private DataProjectionHints() {}

    /**
     * {@code data_table} payload {title?, columns, rows, totals?} from
     * {@code proj.tableOutputs()}. Returns {@code null} unless both {@code
     * columnsPath} and {@code rowsPath} resolve to lists.
     */
    static Map<String, Object> dataTable(ResponseProjection proj, Object result) {
        if (proj == null || proj.tableOutputs() == null) {
            return null;
        }
        Map<String, String> table = proj.tableOutputs();
        String columnsPath = table.get("columnsPath");
        String rowsPath = table.get("rowsPath");
        if (columnsPath == null || rowsPath == null) {
            return null;
        }
        Object columns = SuggestionRuleEvaluator.navigate(result, columnsPath);
        Object rows = SuggestionRuleEvaluator.navigate(result, rowsPath);
        if (!(columns instanceof List<?> cols) || !(rows instanceof List<?> rws)) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        String titlePath = table.get("titlePath");
        if (titlePath != null) {
            Object title = SuggestionRuleEvaluator.navigate(result, titlePath);
            if (title != null) payload.put("title", title);
        }
        payload.put("columns", cols);
        payload.put("rows", rws);
        String totalsPath = table.get("totalsPath");
        if (totalsPath != null) {
            Object totals = SuggestionRuleEvaluator.navigate(result, totalsPath);
            if (totals instanceof Map<?, ?> tm) payload.put("totals", tm);
        }
        return payload;
    }

    /**
     * {@code data_card} payload {title?, rows:[{label,value,type}]} from
     * {@code proj.cardFields()}. A field whose {@code path} is null / misses is
     * skipped; the card emits when ≥1 row resolves, else {@code null}. An
     * optional title reuses {@code tableOutputs.titlePath} when declared.
     */
    static Map<String, Object> dataCard(ResponseProjection proj, Object result) {
        if (proj == null || proj.cardFields() == null || proj.cardFields().isEmpty()) {
            return null;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, String> field : proj.cardFields()) {
            if (field == null) continue;
            String path = field.get("path");
            if (path == null) continue;
            Object value = SuggestionRuleEvaluator.navigate(result, path);
            if (value == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", field.get("label"));
            row.put("value", value);
            row.put("type", field.getOrDefault("type", "text"));
            rows.add(row);
        }
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, String> table = proj.tableOutputs();
        if (table != null && table.get("titlePath") != null) {
            Object title = SuggestionRuleEvaluator.navigate(result, table.get("titlePath"));
            if (title != null) payload.put("title", title);
        }
        payload.put("rows", rows);
        return payload;
    }
}
