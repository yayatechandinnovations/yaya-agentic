package com.yayatechandinnovations.yayaagentic.engine.prompt;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.PromptBuilder;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.HelloWorldProfileBootstrap;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the prompt structure that powers Anthropic prompt caching:
 * <ol>
 *   <li>The cacheable prefix carries items 1–5 from §7 in order.</li>
 *   <li>The variable suffix carries items 6–10.</li>
 *   <li>The prefix is byte-identical across two turns of the same session
 *       (cache hits when sent with cache_control:ephemeral).</li>
 * </ol>
 * Drives the engine directly instead of through the SSE endpoint — this
 * test is about prompt structure, not transport semantics.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, RecordingPromptInspector.class})
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class DefaultPromptBuilderTest {

    @Autowired ConversationEngine engine;
    @Autowired RecordingPromptInspector inspector;

    @Test
    void prefix_contains_section_7_items_in_order() {
        Ids.SessionId sid = startSession();
        drain(sid, "hello");

        PromptBuilder.PromptPayload payload = inspector.lastPayload();
        assertThat(payload).isNotNull();
        String prefix = payload.cacheablePrefix();

        int voice = prefix.indexOf("Voice and tone:");
        int rules = prefix.indexOf("Rules:");
        int role = prefix.indexOf("Role:");
        int caps = prefix.indexOf("Capabilities");
        int tools = prefix.indexOf("Tools (proposed");
        int safety = prefix.indexOf("Safety:");

        assertThat(voice).isGreaterThanOrEqualTo(0);
        assertThat(rules).isGreaterThan(voice);
        assertThat(role).isGreaterThan(rules);
        assertThat(caps).isGreaterThan(role);
        assertThat(tools).isGreaterThan(caps);
        assertThat(safety).isGreaterThan(tools);

        assertThat(payload.variableSuffix()).contains("Session:");
    }

    @Test
    void prefix_is_stable_across_two_turns_of_the_same_session() {
        Ids.SessionId sid = startSession();
        drain(sid, "hello");
        PromptBuilder.PromptPayload first = inspector.lastPayload();
        assertThat(first).isNotNull();

        drain(sid, "anything else?");
        PromptBuilder.PromptPayload second = inspector.lastPayload();
        assertThat(second).isNotNull();

        assertThat(second.cacheablePrefix())
                .as("cacheable prefix must be byte-identical so Anthropic's prompt cache hits")
                .isEqualTo(first.cacheablePrefix());
        // ...and the variable suffix should differ (last_user_message advanced).
        assertThat(second.variableSuffix()).isNotEqualTo(first.variableSuffix());
    }

    @Test
    void two_sessions_share_the_prefix_and_keep_what_is_theirs_in_the_suffix() {
        Ids.SessionId first = startSession();
        drain(first, "hello from the first session");
        PromptBuilder.PromptPayload one = inspector.lastPayload();

        Ids.SessionId second = startSession();
        drain(second, "something else entirely");
        PromptBuilder.PromptPayload two = inspector.lastPayload();

        assertThat(two.cacheablePrefix())
                .as("one profile version = one cached prefix, whoever is talking")
                .isEqualTo(one.cacheablePrefix());
        // Date/time and session data never reach the cached part.
        assertThat(one.cacheablePrefix()).doesNotContain("Fecha y hora actual", "Session:", first.value());
        assertThat(one.variableSuffix()).contains("Fecha y hora actual", "Session:");
        assertThat(two.variableSuffix()).contains("Fecha y hora actual", "Session:");
    }

    @Test
    void the_prefix_names_each_capabilitys_tools_without_repeating_their_schemas() {
        Ids.SessionId sid = startSession();
        drain(sid, "hello");
        String prefix = inspector.lastPayload().cacheablePrefix();

        String tools = prefix.substring(prefix.indexOf("Tools (proposed"), prefix.indexOf("Safety:"));
        assertThat(tools).contains(HelloWorldProfileBootstrap.ECHO.value());
        // The input schema reaches the model once, as the native tool definition.
        assertThat(tools).doesNotContain("input:", "\"type\"");
    }

    // ---- helpers ----------------------------------------------------

    private Ids.SessionId startSession() {
        var result = engine.start(
                new StartConversationRequest(
                        new Ids.TenantId(HelloWorldProfileBootstrap.DEFAULT_TENANT.value()),
                        Optional.of(HelloWorldProfileBootstrap.HELLO_WORLD_PROFILE),
                        "web", Map.of()),
                new AuthContext(HelloWorldProfileBootstrap.DEFAULT_TENANT, Map.of(), Optional.empty()));
        return result.session().id();
    }

    private void drain(Ids.SessionId sid, String text) {
        engine.send(sid, new UserMessage(text, Map.of()),
                new AuthContext(HelloWorldProfileBootstrap.DEFAULT_TENANT, Map.of(), Optional.empty()))
                .blockLast();
    }
}
