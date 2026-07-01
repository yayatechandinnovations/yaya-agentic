package com.yayatechandinnovations.yayaagentic.knowledge;

import com.yayatechandinnovations.yayaagentic.knowledge.ingest.IngestionProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionPropertiesTest {

    @Test
    void applies_defaults_when_unset() {
        var props = new IngestionProperties(null, null, null, null, null);
        assertThat(props.workerEnabled()).isTrue();
        assertThat(props.batchSize()).isEqualTo(4);
        assertThat(props.maxAttempts()).isEqualTo(5);
        assertThat(props.baseBackoff()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.maxBackoff()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void backoff_grows_exponentially_and_caps() {
        var props = new IngestionProperties(true, 4, 5,
                Duration.ofSeconds(30), Duration.ofHours(1));

        assertThat(props.backoffFor(1)).isEqualTo(Duration.ofSeconds(30));   // 30 * 2^0
        assertThat(props.backoffFor(2)).isEqualTo(Duration.ofSeconds(60));   // 30 * 2^1
        assertThat(props.backoffFor(3)).isEqualTo(Duration.ofSeconds(120));  // 30 * 2^2
        assertThat(props.backoffFor(4)).isEqualTo(Duration.ofSeconds(240));
        // 30 * 2^7 = 3840s > 1h cap → clamps
        assertThat(props.backoffFor(8)).isEqualTo(Duration.ofHours(1));
        assertThat(props.backoffFor(50)).isEqualTo(Duration.ofHours(1));     // no overflow
    }
}
