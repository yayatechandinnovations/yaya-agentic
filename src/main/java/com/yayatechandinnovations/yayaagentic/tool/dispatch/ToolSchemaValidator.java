package com.yayatechandinnovations.yayaagentic.tool.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compiles each tool's input JSON Schema once and reuses it. Returns a
 * list of human-readable validation messages; empty list = valid.
 * Drift between Bean handlers and their declared schemas surfaces here,
 * not after the call is dispatched.
 */
@Component
public class ToolSchemaValidator {

    private final ObjectMapper json;
    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final Map<String, JsonSchema> compiledByTool = new ConcurrentHashMap<>();

    public ToolSchemaValidator(ObjectMapper json) {
        this.json = json;
    }

    public List<String> validateInput(ToolDescriptor descriptor, Map<String, Object> args) {
        JsonSchema schema = compiledByTool.computeIfAbsent(
                descriptor.id().value() + "#input",
                k -> factory.getSchema(descriptor.inputSchemaJson()));
        JsonNode argsNode = json.valueToTree(args == null ? Map.of() : args);
        Set<ValidationMessage> messages = schema.validate(argsNode);
        if (messages.isEmpty()) return List.of();
        return messages.stream().map(ValidationMessage::getMessage).toList();
    }
}
