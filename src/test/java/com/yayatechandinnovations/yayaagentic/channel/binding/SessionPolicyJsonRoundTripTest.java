package com.yayatechandinnovations.yayaagentic.channel.binding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Defends the JSON shape of {@link SessionPolicy} as persisted inside
 * {@code channel_bindings.session_policy_json}. JSR-310 is registered
 * explicitly because the production {@code ObjectMapper} is Spring-auto-
 * configured with the module; an isolated unit test must mirror that or
 * every Duration field rejects deserialization.
 */
class SessionPolicyJsonRoundTripTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void defaults_round_trip_lossless() throws Exception {
        SessionPolicy original = SessionPolicy.defaults();
        String encoded = json.writeValueAsString(original);
        SessionPolicy decoded = json.readValue(encoded, SessionPolicy.class);
        assertThat(decoded.idleTimeout()).isEqualTo(Duration.ofHours(24));
        assertThat(decoded.newSessionKeywords()).containsExactlyInAnyOrderElementsOf(
                SessionPolicy.DEFAULT_NEW_SESSION_KEYWORDS);
        assertThat(decoded.endSessionKeywords()).containsExactlyInAnyOrderElementsOf(
                SessionPolicy.DEFAULT_END_SESSION_KEYWORDS);
        assertThat(decoded.exposeEndUserPhoneToProfile()).isTrue();
    }

    @Test
    void custom_idle_round_trips() throws Exception {
        SessionPolicy original = new SessionPolicy(
                Duration.ofHours(12),
                Set.of("#new"),
                Set.of("bye"),
                false, null, false);
        String encoded = json.writeValueAsString(original);
        SessionPolicy decoded = json.readValue(encoded, SessionPolicy.class);
        assertThat(decoded.idleTimeout()).isEqualTo(Duration.ofHours(12));
        assertThat(decoded.exposeEndUserPhoneToProfile()).isFalse();
    }
}
