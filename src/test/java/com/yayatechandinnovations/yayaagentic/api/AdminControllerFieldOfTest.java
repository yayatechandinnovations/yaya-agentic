package com.yayatechandinnovations.yayaagentic.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Validation messages name the request field the console puts them under. */
class AdminControllerFieldOfTest {

    @Test
    void resource_prefixed_messages_map_to_the_wire_field() {
        assertThat(AdminController.fieldOf("profile.displayName is required")).isEqualTo("displayName");
        assertThat(AdminController.fieldOf("tool.handler.beanName is required for BEAN tools")).isEqualTo("handler.beanName");
        assertThat(AdminController.fieldOf("tool.inputSchemaJson is not valid JSON: x")).isEqualTo("inputSchemaJson");
        assertThat(AdminController.fieldOf("tool.captures[2] is null")).isEqualTo("captures[2]");
        assertThat(AdminController.fieldOf("recordingStrategy.scopeKind must be TENANT or PROFILE")).isEqualTo("scopeKind");
    }

    @Test
    void reference_errors_point_at_the_list_field() {
        assertThat(AdminController.fieldOf("profile references unknown capability: x")).isEqualTo("capabilities");
        assertThat(AdminController.fieldOf("profile references unknown auth binding: x")).isEqualTo("authBindingId");
        assertThat(AdminController.fieldOf("capability references unknown tool: x")).isEqualTo("tools");
        assertThat(AdminController.fieldOf("httpSpec.method 'FOO' is not a valid HTTP method")).isEqualTo("handler.httpSpec.method");
    }

    @Test
    void messages_without_a_field_stay_form_level() {
        assertThat(AdminController.fieldOf("strategy.kind must be one of single|fanout|tiered|classified")).isNull();
        assertThat(AdminController.fieldOf(null)).isNull();
    }
}
