package com.yayatechandinnovations.yayaagentic.tool.dispatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles each tool's input JSON Schema once and reuses it. Splits the
 * violation set into missing-required-field violations (which the M2
 * elicitation guardrail handles by asking the user) and everything else
 * (handed to the dispatcher as a FAILED tool result for the engine to
 * surface).
 */
@Component
public class ToolSchemaValidator {

    /** networknt 2020-12 emits required-field violations of the form
     *  {@code $.<field>: required property '<field>' not found ...}.
     *  We capture the field name. */
    private static final Pattern REQUIRED_MISSING =
            Pattern.compile("required property '([^']+)' not found", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper json;
    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final Map<String, JsonSchema> compiledByTool = new ConcurrentHashMap<>();

    public ToolSchemaValidator(ObjectMapper json) {
        this.json = json;
    }

    /**
     * Backwards-compat helper — returns ALL violation messages. Kept for
     * tests and admin previews; the engine path uses {@link #validate}.
     */
    public List<String> validateInput(ToolDescriptor descriptor, Map<String, Object> args) {
        return validate(descriptor, args).allMessages();
    }

    /** Structured outcome: missing required fields separated from other issues. */
    public Result validate(ToolDescriptor descriptor, Map<String, Object> args) {
        JsonSchema schema = compiledByTool.computeIfAbsent(
                descriptor.id().value() + "#input",
                k -> factory.getSchema(descriptor.inputSchemaJson()));
        JsonNode argsNode = json.valueToTree(args == null ? Map.of() : args);
        Set<ValidationMessage> messages = schema.validate(argsNode);

        List<String> missing = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (ValidationMessage m : messages) {
            Matcher mm = REQUIRED_MISSING.matcher(m.getMessage());
            if (mm.find()) missing.add(mm.group(1));
            else others.add(m.getMessage());
        }
        return new Result(missing, others);
    }

    public record Result(List<String> missingRequired, List<String> otherViolations) {
        public boolean isClean() { return missingRequired.isEmpty() && otherViolations.isEmpty(); }
        public boolean needsElicitation() { return !missingRequired.isEmpty(); }
        public List<String> allMessages() {
            List<String> all = new ArrayList<>(missingRequired.size() + otherViolations.size());
            for (String m : missingRequired) all.add("missing required field: " + m);
            all.addAll(otherViolations);
            return all;
        }
    }
}
