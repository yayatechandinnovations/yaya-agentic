package com.yayatechandinnovations.yayaagentic.tool.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonPointerExtractorTest {

    private final JsonPointerExtractor extractor = new JsonPointerExtractor(new ObjectMapper());

    @Test
    void extracts_top_level_string_from_map() {
        Object out = extractor.extract(Map.of("ticketId", "T-9"), "/ticketId");
        assertThat(out).isEqualTo("T-9");
    }

    @Test
    void extracts_nested_value() {
        Object out = extractor.extract(
                Map.of("ticket", Map.of("id", "T-9", "ref", "R-7")),
                "/ticket/id");
        assertThat(out).isEqualTo("T-9");
    }

    @Test
    void extracts_array_index() {
        Object out = extractor.extract(
                Map.of("lines", List.of(Map.of("id", "L-1"), Map.of("id", "L-2"))),
                "/lines/1/id");
        assertThat(out).isEqualTo("L-2");
    }

    @Test
    void returns_null_for_missing_path_not_an_exception() {
        assertThat(extractor.extract(Map.of("a", 1), "/b/c/d")).isNull();
    }

    @Test
    void returns_null_for_json_null_value() {
        // The "is null" branch needs to be distinguished from "present and 0"
        // — capture treats null as "nothing to capture", not "capture null".
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("x", null);
        assertThat(extractor.extract(map, "/x")).isNull();
    }

    @Test
    void parses_json_string_source() {
        Object out = extractor.extract("{\"ticketId\":\"T-9\"}", "/ticketId");
        assertThat(out).isEqualTo("T-9");
    }

    @Test
    void preserves_integer_type_for_numeric_fields() {
        Object out = extractor.extract(Map.of("lotteryId", 17), "/lotteryId");
        assertThat(out).isEqualTo(17);
    }

    @Test
    void empty_or_invalid_pointer_returns_null_not_exception() {
        assertThat(extractor.extract(Map.of("a", 1), "")).isNull();
        assertThat(extractor.extract(Map.of("a", 1), "no-leading-slash")).isNull();
    }
}
