package com.yayatechandinnovations.yayaagentic.knowledge;

import com.yayatechandinnovations.yayaagentic.knowledge.blob.KnowledgeUploadProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeUploadPropertiesTest {

    @Test
    void applies_safe_defaults_when_unset() {
        var props = new KnowledgeUploadProperties(null, null, null, null, null, false);

        assertThat(props.blobStore()).isEqualTo("db");
        assertThat(props.maxFileBytes()).isEqualTo(25L * 1024 * 1024);
        assertThat(props.maxRequestBytes()).isEqualTo(100L * 1024 * 1024);
        assertThat(props.maxTenantCorpusBytes()).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(props.allowedContentTypes()).contains("application/pdf", "text/markdown");
    }

    @Test
    void allow_list_ignores_charset_parameters_and_case() {
        var props = new KnowledgeUploadProperties(null, null, null, null, null, false);

        assertThat(props.isAllowed("text/plain; charset=UTF-8")).isTrue();
        assertThat(props.isAllowed("APPLICATION/PDF")).isTrue();
        assertThat(props.isAllowed("application/x-msdownload")).isFalse();
        assertThat(props.isAllowed("image/svg+xml")).isFalse();
        assertThat(props.isAllowed(null)).isFalse();
    }
}
