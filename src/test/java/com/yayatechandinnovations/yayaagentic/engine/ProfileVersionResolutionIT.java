package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.HelloWorldProfileBootstrap;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.ProfileRegistry;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Latest-active profile version resolution (design §5.2).
 *
 * <p>An unpinned resolution ({@link Ids.ProfileId#isLatest()}) is bound to a
 * concrete version exactly once, at {@code start()} — new sessions pick up a
 * newly-published version, but an open session NEVER jumps versions mid-stream.
 * A pinned resolution is honored verbatim even when a newer version exists.</p>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class ProfileVersionResolutionIT {

    private static final Ids.TenantId TENANT = HelloWorldProfileBootstrap.DEFAULT_TENANT;

    @Autowired ConversationEngine engine;
    @Autowired ProfileRegistry profiles;

    @Test
    void unpinned_start_serves_the_latest_published_version() {
        register("latest-two", 1, "V1-SYSTEM");
        register("latest-two", 2, "V2-SYSTEM");

        StartSessionResult started = start(Ids.ProfileId.latest("latest-two"));

        // The persisted session must carry the CONCRETE version, never the
        // "latest" sentinel (version 0).
        assertThat(started.profile().version()).isEqualTo(2);
        assertThat(started.profile().isLatest()).isFalse();
        assertThat(started.session().profile().version()).isEqualTo(2);
    }

    @Test
    void explicit_pin_is_honored_even_when_a_newer_version_exists() {
        register("pinned-old", 1, "V1-SYSTEM");
        register("pinned-old", 2, "V2-SYSTEM");

        StartSessionResult started = start(new Ids.ProfileId("pinned-old", 1));

        assertThat(started.profile().version()).isEqualTo(1);
    }

    @Test
    void an_open_session_keeps_its_version_when_a_newer_one_is_published() {
        register("no-jump", 1, "SYSTEM-MARKER-V1");

        // Session A opens while only v1 exists → pinned to v1.
        StartSessionResult sessionA = start(Ids.ProfileId.latest("no-jump"));
        assertThat(sessionA.profile().version()).isEqualTo(1);

        // A new version is published mid-conversation.
        register("no-jump", 2, "SYSTEM-MARKER-V2");

        // A brand-new session B DOES pick up v2 (proves publish took effect).
        StartSessionResult sessionB = start(Ids.ProfileId.latest("no-jump"));
        assertThat(sessionB.profile().version()).isEqualTo(2);

        // Session A, already open, must still be served v1 on its next turn:
        // the prompt built for its send() carries v1's system fragment.
        drain(sessionA.session().id(), "hi");
        String prefixA = engine.lastPrompt(sessionA.session().id()).orElseThrow().cacheablePrefix();
        assertThat(prefixA)
                .as("open session must not jump to the newly-published version")
                .contains("SYSTEM-MARKER-V1")
                .doesNotContain("SYSTEM-MARKER-V2");
    }

    // ---- helpers ------------------------------------------------------------

    private StartSessionResult start(Ids.ProfileId profile) {
        return engine.start(
                new StartConversationRequest(TENANT, Optional.of(profile), "web", Map.of()),
                new AuthContext(TENANT, Map.of(), Optional.empty()));
    }

    private void drain(Ids.SessionId sid, String text) {
        engine.send(sid, new UserMessage(text, Map.of()),
                        new AuthContext(TENANT, Map.of(), Optional.empty()))
                .blockLast();
    }

    private void register(String id, int version, String systemPrompt) {
        profiles.register(new Profile(
                new Ids.ProfileId(id, version),
                TENANT,
                id + " v" + version,
                "hello from " + id,
                systemPrompt,
                List.of(),
                List.of(),
                null,
                "en",
                Map.of()));
    }
}
