package com.yayatechandinnovations.yayaagentic.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.ToolSchemaValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSchemaValidatorTest {

    private final ToolSchemaValidator validator = new ToolSchemaValidator(new ObjectMapper());

    @Test
    void rejects_when_required_arg_missing() {
        ToolDescriptor descriptor = echoDescriptor();

        List<String> violations = validator.validateInput(descriptor, Map.of());

        assertThat(violations).isNotEmpty();
        assertThat(String.join(" ", violations)).contains("text");
    }

    @Test
    void accepts_valid_input() {
        ToolDescriptor descriptor = echoDescriptor();

        List<String> violations = validator.validateInput(descriptor, Map.of("text", "hello"));

        assertThat(violations).isEmpty();
    }

    private static ToolDescriptor echoDescriptor() {
        return new ToolDescriptor(
                new Ids.ToolId("echo"),
                "{\"type\":\"object\",\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"}}}",
                "{\"type\":\"object\"}",
                PermissionRequirement.none(),
                new ToolHandlerRef.Bean("echoTool"),
                ToolPolicy.defaults());
    }
}
