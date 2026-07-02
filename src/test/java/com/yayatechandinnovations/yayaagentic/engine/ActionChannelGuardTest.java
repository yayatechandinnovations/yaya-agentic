package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Two doors, one lock (§9): the action channel must refuse any tool that isn't
 * in the session profile AND {@code clientInvocable}, and must fail closed on an
 * unknown session — the tap is an untrusted proposal, not a trusted shortcut.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class ActionChannelGuardTest {

    @Autowired
    ConversationEngine engine;

    private AuthContext auth() {
        return new AuthContext(HelloWorldProfileBootstrap.DEFAULT_TENANT, Map.of(), Optional.empty());
    }

    private Ids.SessionId startSession() {
        return engine.start(new StartConversationRequest(
                        HelloWorldProfileBootstrap.DEFAULT_TENANT,
                        Optional.of(HelloWorldProfileBootstrap.HELLO_WORLD_PROFILE),
                        "web", Map.of()),
                auth()).session().id();
    }

    @Test
    void refusesAToolThatIsNotAClientInvocableProfileTool() {
        Ids.SessionId sid = startSession();
        // A tool the HelloWorld profile doesn't expose as a tappable action —
        // refused with the opaque ActionNotPermittedException (no leak).
        assertThrows(ActionNotPermittedException.class, () ->
                engine.dispatchAction(sid, new Ids.ToolId("add_to_cart"), Map.of(), auth()).blockLast());
    }

    @Test
    void refusesAnUnknownToolId() {
        Ids.SessionId sid = startSession();
        assertThrows(ActionNotPermittedException.class, () ->
                engine.dispatchAction(sid, new Ids.ToolId("definitely_not_a_tool"), Map.of(), auth())
                        .blockLast());
    }

    @Test
    void failsClosedOnUnknownSession() {
        assertThrows(IllegalStateException.class, () ->
                engine.dispatchAction(new Ids.SessionId(UUID.randomUUID().toString()),
                        new Ids.ToolId("add_to_cart"), Map.of(), auth()).blockLast());
    }
}
