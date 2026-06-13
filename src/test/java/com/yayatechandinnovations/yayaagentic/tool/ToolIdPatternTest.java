package com.yayatechandinnovations.yayaagentic.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolIdPatternTest {

    @Test
    void isValid_acceptsAnthropicCompatibleIds() {
        assertThat(ToolIdPattern.isValid("get_balance")).isTrue();
        assertThat(ToolIdPattern.isValid("payments-init")).isTrue();
        assertThat(ToolIdPattern.isValid("a")).isTrue();
        assertThat(ToolIdPattern.isValid("X9_z-Q")).isTrue();
        assertThat(ToolIdPattern.isValid("a".repeat(128))).isTrue();
    }

    @Test
    void isValid_rejectsOffenders() {
        assertThat(ToolIdPattern.isValid("payments.init")).isFalse();
        assertThat(ToolIdPattern.isValid("get balance")).isFalse();
        assertThat(ToolIdPattern.isValid("foo/bar")).isFalse();
        assertThat(ToolIdPattern.isValid("a@b")).isFalse();
        assertThat(ToolIdPattern.isValid("")).isFalse();
        assertThat(ToolIdPattern.isValid(null)).isFalse();
        assertThat(ToolIdPattern.isValid("a".repeat(129))).isFalse();
    }

    @Test
    void sanitize_replacesBadCharsWithUnderscore() {
        assertThat(ToolIdPattern.sanitize("payments.init")).isEqualTo("payments_init");
        assertThat(ToolIdPattern.sanitize("get balance")).isEqualTo("get_balance");
        assertThat(ToolIdPattern.sanitize("foo/bar/baz")).isEqualTo("foo_bar_baz");
    }

    @Test
    void sanitize_collapsesRepeatedUnderscores() {
        assertThat(ToolIdPattern.sanitize("foo..bar")).isEqualTo("foo_bar");
        assertThat(ToolIdPattern.sanitize("a.b.c.d")).isEqualTo("a_b_c_d");
        assertThat(ToolIdPattern.sanitize("a__b")).isEqualTo("a_b");
    }

    @Test
    void sanitize_trimsEdgeUnderscores() {
        assertThat(ToolIdPattern.sanitize(".foo.")).isEqualTo("foo");
        assertThat(ToolIdPattern.sanitize("___foo___")).isEqualTo("foo");
    }

    @Test
    void sanitize_returnsNullWhenInputIsEmptyOrAllBad() {
        assertThat(ToolIdPattern.sanitize(null)).isNull();
        assertThat(ToolIdPattern.sanitize("")).isNull();
        assertThat(ToolIdPattern.sanitize("...")).isNull();
        assertThat(ToolIdPattern.sanitize("@@@")).isNull();
    }

    @Test
    void sanitize_truncatesTo128Chars() {
        String input = "a.".repeat(200);   // 400 chars before sanitise, 199 after collapse
        String out = ToolIdPattern.sanitize(input);
        assertThat(out).hasSize(128);
        assertThat(ToolIdPattern.isValid(out)).isTrue();
    }

    @Test
    void sanitize_isIdempotentForValidIds() {
        assertThat(ToolIdPattern.sanitize("already_valid")).isEqualTo("already_valid");
        assertThat(ToolIdPattern.sanitize("kebab-case-ok")).isEqualTo("kebab-case-ok");
    }
}
