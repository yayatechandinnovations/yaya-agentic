package com.yayatechandinnovations.yayaagentic.knowledge;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthzContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.auth.Authorizer;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
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
 * End-to-end retrieval scenarios driven through the engine:
 * <ul>
 *   <li>A profile with an attached source emits {@code Citation} SSE events.</li>
 *   <li>When the {@code Authorizer} chain denies the source's access
 *       requirement, the source is silently dropped — no citations, no
 *       error to the user — and {@code lastRetrieval().sourcesDenied}
 *       names the dropped source for the operator.</li>
 * </ul>
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, RetrievalScenariosTest.DenySourcesConfig.class})
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key=",
        "spring.main.allow-bean-definition-overriding=true"
})
class RetrievalScenariosTest {

    @Autowired ConversationEngine engine;
    @Autowired SourceDenyingAuthorizer denySources;

    @Test
    void retrieval_emits_citation_events_for_attached_sources() {
        denySources.allowAll();
        Ids.SessionId sid = startSession();
        List<TurnEvent> events = drain(sid, "what is Yaya?");

        long citationCount = events.stream()
                .filter(e -> e instanceof TurnEvent.Citation)
                .count();
        assertThat(citationCount)
                .as("hello-world has the yaya-faq source attached; "
                        + "always-on gating must have surfaced at least one citation")
                .isGreaterThan(0);

        var retrieval = engine.lastRetrieval(sid);
        assertThat(retrieval).isPresent();
        assertThat(retrieval.get().chunks()).isNotEmpty();
        assertThat(retrieval.get().trace().sourcesDenied())
                .as("with no denial armed, no sources should be denied")
                .isEmpty();
    }

    @Test
    void source_authz_denial_silently_drops_the_source() {
        denySources.denyAllSources();
        Ids.SessionId sid = startSession();
        List<TurnEvent> events = drain(sid, "what can you do?");

        long citationCount = events.stream()
                .filter(e -> e instanceof TurnEvent.Citation)
                .count();
        assertThat(citationCount)
                .as("the only attached source is denied — no citations should reach the user")
                .isEqualTo(0);

        var retrieval = engine.lastRetrieval(sid);
        assertThat(retrieval).isPresent();
        assertThat(retrieval.get().chunks()).isEmpty();
        assertThat(retrieval.get().trace().sourcesConsidered())
                .as("the profile-attached source is still in considered — "
                        + "the trace tells the operator something was filtered")
                .isNotEmpty();
        assertThat(retrieval.get().trace().sourcesDenied())
                .as("denied list names the silently-dropped source")
                .isNotEmpty();
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
    static class DenySourcesConfig {
        @Bean(name = "authorizerChain")
        @Primary
        SourceDenyingAuthorizer authorizerChainOverride() { return new SourceDenyingAuthorizer(); }

        @Bean
        SourceDenyingAuthorizer denySources(SourceDenyingAuthorizer authorizerChainOverride) {
            return authorizerChainOverride;
        }
    }

    /**
     * Authorizer that selectively denies knowledge-source AuthZ calls. The
     * {@code AuthzContext.attributes()} carries a {@code knowledgeSourceId}
     * key when the retriever calls in, so we can scope the deny to that path
     * without affecting tool dispatch.
     */
    static class SourceDenyingAuthorizer implements Authorizer {
        private final AtomicBoolean denySources = new AtomicBoolean(false);

        void allowAll() { denySources.set(false); }
        void denyAllSources() { denySources.set(true); }

        @Override
        public AuthzDecision authorize(Principal principal, PermissionRequirement requirement,
                                       Object args, AuthzContext ctx) {
            boolean isSourceCall = ctx != null && ctx.attributes() != null
                    && ctx.attributes().containsKey("knowledgeSourceId");
            if (isSourceCall && denySources.get()) {
                return new AuthzDecision.Deny(
                        "you don't have access to that knowledge",
                        "test: knowledge sources globally denied");
            }
            return AuthzDecision.Allow.none();
        }
    }
}
