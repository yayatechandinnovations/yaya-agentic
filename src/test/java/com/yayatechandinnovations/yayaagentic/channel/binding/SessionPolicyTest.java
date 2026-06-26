package com.yayatechandinnovations.yayaagentic.channel.binding;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Defends the SessionPolicy invariants: idle bounds, default keywords,
 * reengagement template requirement.
 */
class SessionPolicyTest {

    @Test
    void defaults_use_24h_idle_and_standard_keywords() {
        SessionPolicy defaults = SessionPolicy.defaults();
        assertThat(defaults.idleTimeout()).isEqualTo(Duration.ofHours(24));
        assertThat(defaults.newSessionKeywords()).contains("#new", "restart", "start over");
        assertThat(defaults.endSessionKeywords()).contains("bye", "done", "thanks bye");
        assertThat(defaults.exposeEndUserPhoneToProfile()).isTrue();
        assertThat(defaults.greetOnReengagement()).isFalse();
    }

    @Test
    void zero_or_negative_idle_is_rejected() {
        assertThatThrownBy(() -> new SessionPolicy(
                Duration.ZERO, Set.of(), Set.of(), false, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idleTimeout must be positive");
    }

    @Test
    void idle_beyond_168h_is_rejected() {
        assertThatThrownBy(() -> new SessionPolicy(
                Duration.ofHours(200), Set.of(), Set.of(), false, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idleTimeout exceeds");
    }

    @Test
    void greet_requires_template() {
        assertThatThrownBy(() -> new SessionPolicy(
                Duration.ofHours(24), Set.of(), Set.of(), true, "", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reengagementTemplate is required");
    }
}
