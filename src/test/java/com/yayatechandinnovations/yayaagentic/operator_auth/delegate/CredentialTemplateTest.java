package com.yayatechandinnovations.yayaagentic.operator_auth.delegate;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialTemplateTest {

    @Test
    void json_format_escapes_quotes_and_backslashes_in_password() {
        // A password that would break the JSON envelope if pasted in raw.
        String body = CredentialTemplate.renderBody(
                RequestShape.BodyFormat.JSON,
                "{\"u\":\"{{username}}\",\"p\":\"{{password}}\"}",
                "alice", "p\"as\\sword".toCharArray());

        assertThat(body).isEqualTo("{\"u\":\"alice\",\"p\":\"p\\\"as\\\\sword\"}");
    }

    @Test
    void form_format_percent_encodes_specials() {
        String body = CredentialTemplate.renderBody(
                RequestShape.BodyFormat.FORM,
                "username={{username}}&password={{password}}",
                "al ice", "p&w=ord".toCharArray());

        assertThat(body).isEqualTo("username=al+ice&password=p%26w%3Dord");
    }

    @Test
    void basic_auth_helper_encodes_user_colon_password() {
        String header = CredentialTemplate.basicAuthHeader("alice", "s3cret".toCharArray());

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("alice:s3cret".getBytes(StandardCharsets.UTF_8));
        assertThat(header).isEqualTo(expected);
    }

    @Test
    void none_and_basic_auth_formats_produce_no_body() {
        assertThat(CredentialTemplate.renderBody(
                RequestShape.BodyFormat.NONE, "ignored", "u", "p".toCharArray())).isNull();
        assertThat(CredentialTemplate.renderBody(
                RequestShape.BodyFormat.BASIC_AUTH, "ignored", "u", "p".toCharArray())).isNull();
    }

    @Test
    void header_substitution_strips_cr_lf_to_block_injection() {
        // An operator (or worse — username) trying to inject a header.
        String value = CredentialTemplate.renderHeaderValue(
                "tenant={{username}}", "alice\r\nX-Spoof: evil", null);

        assertThat(value).isEqualTo("tenant=aliceX-Spoof: evil");
        assertThat(value).doesNotContain("\r");
        assertThat(value).doesNotContain("\n");
    }

    @Test
    void empty_template_falls_back_to_format_defaults() {
        String json = CredentialTemplate.renderBody(
                RequestShape.BodyFormat.JSON, null, "alice", "s3cret".toCharArray());
        assertThat(json).isEqualTo("{\"username\":\"alice\",\"password\":\"s3cret\"}");

        String form = CredentialTemplate.renderBody(
                RequestShape.BodyFormat.FORM, "", "alice", "s3cret".toCharArray());
        assertThat(form).isEqualTo("username=alice&password=s3cret");
    }
}
