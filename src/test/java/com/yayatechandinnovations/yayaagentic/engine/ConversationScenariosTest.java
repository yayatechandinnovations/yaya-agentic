package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.Authorizer;
import com.yayatechandinnovations.yayaagentic.auth.AuthzContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.HelloWorldProfileBootstrap;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the two M2-C flows. Drives the engine directly
 * (no SSE wire transport) and inspects the emitted TurnEvent sequence.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, ConversationScenariosTest.DenyOnceConfig.class})
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        // The test config overrides the @Primary AuthorizerChain by name
        // so the engine consumes DenyOnceAuthorizer. The chain itself stays
        // covered by AuthorizerChainTest.
        "spring.main.allow-bean-definition-overriding=true"
})
class ConversationScenariosTest {

    @Autowired ConversationEngine engine;
    @Autowired DenyOnceAuthorizer denyOnce;

    @Test
    void bare_echo_elicits_a_focused_question_and_then_resumes_on_reply() {
        Ids.SessionId sid = startSession();

        List<TurnEvent> firstTurn = drain(sid, "echo");
        // No tool_call should have been emitted — we never even tried to dispatch.
        assertThat(firstTurn).noneMatch(e -> e instanceof TurnEvent.ToolCall);
        TurnEvent.Token question = firstTurn.stream()
                .filter(e -> e instanceof TurnEvent.Token)
                .map(e -> (TurnEvent.Token) e)
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(question.text().toLowerCase()).contains("text");

        // The reply fills the missing slot; the dispatch resumes against the echo tool.
        List<TurnEvent> secondTurn = drain(sid, "phase-c");
        assertThat(secondTurn).filteredOn(e -> e instanceof TurnEvent.ToolCall).hasSize(1);
        TurnEvent.ToolResult result = secondTurn.stream()
                .filter(e -> e instanceof TurnEvent.ToolResult)
                .map(e -> (TurnEvent.ToolResult) e)
                .findFirst().orElseThrow();
        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.OK);
        assertThat(result.value().toString()).contains("phase-c");
    }

    @Test
    void denial_emits_a_structured_tool_result_and_an_llm_paraphrased_token() {
        Ids.SessionId sid = startSession();
        denyOnce.armOnce("that resource isn't linked to your profile");

        List<TurnEvent> turn = drain(sid, "echo nope");

        TurnEvent.ToolResult result = turn.stream()
                .filter(e -> e instanceof TurnEvent.ToolResult)
                .map(e -> (TurnEvent.ToolResult) e)
                .findFirst().orElseThrow();

        assertThat(result.status()).isEqualTo(Turn.ToolResult.Status.DENIED);
        assertThat(result.value().toString())
                .as("denial payload carries the user-safe reason for the audit / UI")
                .contains("isn't linked to your profile");

        // The LLM continuation round sees the DENIED tool_result in its
        // history and paraphrases the refusal in its own voice — no hardcoded
        // refusal template is injected by the engine in the LLM-driven path.
        // The stub paraphrase format is "That didn't work: <payload>".
        TurnEvent.Token tail = turn.stream()
                .filter(e -> e instanceof TurnEvent.Token)
                .map(e -> (TurnEvent.Token) e)
                .reduce((a, b) -> b).orElseThrow();
        assertThat(tail.text())
                .as("LLM-paraphrased token — driven by the DENIED tool_result, "
                        + "not the engine's hardcoded refusal template")
                .containsIgnoringCase("didn't work")
                .contains("isn't linked to your profile")
                .doesNotContain("scope check")
                .doesNotContain("ownership check");
    }

    // ---- helpers ----------------------------------------------------

    private Ids.SessionId startSession() {
        return engine.start(
                new StartConversationRequest(
                        HelloWorldProfileBootstrap.DEFAULT_TENANT,
                        Optional.of(HelloWorldProfileBootstrap.HELLO_WORLD_PROFILE),
                        "web", Map.of()),
                new AuthContext(HelloWorldProfileBootstrap.DEFAULT_TENANT, Map.of(), Optional.empty())
        ).session().id();
    }

    private List<TurnEvent> drain(Ids.SessionId sid, String text) {
        List<TurnEvent> out = new ArrayList<>();
        engine.send(sid, new UserMessage(text, Map.of()),
                new AuthContext(HelloWorldProfileBootstrap.DEFAULT_TENANT, Map.of(), Optional.empty()))
                .doOnNext(out::add)
                .blockLast();
        return out;
    }

    // ---- test config ------------------------------------------------

    @TestConfiguration(proxyBeanMethods = false)
    static class DenyOnceConfig {
        /** Overrides the AuthorizerChain bean by name (requires
         *  spring.main.allow-bean-definition-overriding=true). The engine
         *  reads the @Primary bean of type Authorizer; with the chain
         *  swapped out, that's the deny-once shim. */
        @Bean(name = "authorizerChain")
        @Primary
        DenyOnceAuthorizer authorizerChainOverride() { return new DenyOnceAuthorizer(); }

        @Bean
        DenyOnceAuthorizer denyOnce(DenyOnceAuthorizer authorizerChainOverride) {
            return authorizerChainOverride;
        }
    }

    /**
     * Test-only Authorizer that, when armed, returns one DENY for the next
     * call against the {@code echo} tool and reverts to Allow.none() after.
     * Scoped to the echo tool so M2.5-onwards retrieval-source AuthZ calls
     * (every turn iterates each profile-attached source through the chain)
     * don't accidentally consume the armed deny.
     * <p>
     * Marked {@code @Primary} so the engine consumes it instead of the real
     * chain (the chain remains constructed and tested elsewhere). No
     * delegation, no cycles.
     */
    static class DenyOnceAuthorizer implements Authorizer {
        private final AtomicBoolean armed = new AtomicBoolean(false);
        private volatile String userSafeReason = "denied";

        void armOnce(String userSafeReason) {
            this.userSafeReason = userSafeReason;
            this.armed.set(true);
        }

        @Override
        public AuthzDecision authorize(Principal principal, PermissionRequirement requirement,
                                       Object args, AuthzContext ctx) {
            boolean targetingEcho = ctx != null && ctx.attributes() != null
                    && "echo".equals(ctx.attributes().get("toolId"));
            if (targetingEcho && armed.compareAndSet(true, false)) {
                return new AuthzDecision.Deny(userSafeReason, "test: armed deny");
            }
            return AuthzDecision.Allow.none();
        }
    }
}
