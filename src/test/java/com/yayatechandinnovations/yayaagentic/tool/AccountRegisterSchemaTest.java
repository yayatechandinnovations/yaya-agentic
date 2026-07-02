package com.yayatechandinnovations.yayaagentic.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the additive Signal 2 change to the {@code account_register} tool's
 * output schema: {@code tokenRefreshRequired} is declared, so a registration
 * response carrying it still validates under {@code additionalProperties:false}.
 */
class AccountRegisterSchemaTest {

    private final ObjectMapper json = new ObjectMapper();
    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private JsonNode outputSchemaNode() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/tenant-tools/account_register.json")) {
            assertThat(in).as("account_register.json fixture on the classpath").isNotNull();
            return json.readTree(in).get("outputSchema");
        }
    }

    @Test
    void output_schema_locks_additional_properties_and_declares_the_new_field() throws Exception {
        JsonNode schema = outputSchemaNode();
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("properties").has("tokenRefreshRequired")).isTrue();
        assertThat(schema.path("properties").path("tokenRefreshRequired").path("type").asText())
                .isEqualTo("boolean");
    }

    @Test
    void response_with_token_refresh_required_validates() throws Exception {
        JsonSchema schema = factory.getSchema(outputSchemaNode());
        JsonNode response = json.readTree(
                "{\"customerRef\":\"cust-9\",\"accountState\":\"registered\",\"tokenRefreshRequired\":true}");

        Set<ValidationMessage> messages = schema.validate(response);

        assertThat(messages).isEmpty();
    }

    @Test
    void older_response_without_the_new_field_still_validates() throws Exception {
        JsonSchema schema = factory.getSchema(outputSchemaNode());
        JsonNode response = json.readTree(
                "{\"customerRef\":\"cust-9\",\"accountState\":\"guest\"}");

        assertThat(schema.validate(response)).isEmpty();
    }

    @Test
    void an_undeclared_field_is_still_rejected() throws Exception {
        // Proves additionalProperties:false is genuinely in force — the reason we
        // had to declare tokenRefreshRequired rather than rely on lax validation.
        JsonSchema schema = factory.getSchema(outputSchemaNode());
        JsonNode response = json.readTree(
                "{\"customerRef\":\"cust-9\",\"accountState\":\"registered\",\"bogus\":1}");

        assertThat(schema.validate(response)).isNotEmpty();
    }
}
