package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.core.TurnChannel;
import com.yayatechandinnovations.yayaagentic.engine.action.BoundActionStore;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.HelloWorldProfileBootstrap;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.M0Catalog;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import com.yayatechandinnovations.yayaagentic.tool.ToolDescriptor;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandlerRef;
import com.yayatechandinnovations.yayaagentic.tool.ToolPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The channel a tool is told a turn came in on ({@code {session.channel}}): the
 * surface that received the message or the tap, in the fixed vocabulary, and
 * never the free label a session was created with.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TurnChannelTest.Config.class})
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class TurnChannelTest {

    private static final String SUBJECT = "chat:+18095550001";

    @Autowired ConversationEngine engine;
    @Autowired M0Catalog catalog;
    @Autowired ChannelProbe probe;

    private final OneConfirmationAndBoundActionScenariosTest.InMemoryActions actions =
            new OneConfirmationAndBoundActionScenariosTest.InMemoryActions();
    private ToolDescriptor originalEcho;

    @BeforeEach
    void setUp() {
        originalEcho = catalog.tool(HelloWorldProfileBootstrap.ECHO).orElseThrow();
        catalog.registerTool(new ToolDescriptor(
                HelloWorldProfileBootstrap.ECHO,
                "{\"type\":\"object\"}", "{\"type\":\"object\"}",
                PermissionRequirement.none(),
                new ToolHandlerRef.Bean("turnChannelProbe"),
                new ToolPolicy(Duration.ofSeconds(10), 0, true, true, false, null, false, false, true)));
        ((DefaultConversationEngine) engine).setBoundActions(actions);
        probe.channels.clear();
    }

    @AfterEach
    void tearDown() {
        catalog.registerTool(originalEcho);
        ((DefaultConversationEngine) engine).setBoundActions(null);
    }

    @ParameterizedTest(name = "session \"{0}\", turn received on \"{1}\" -> {2}")
    @CsvSource(nullValues = "-", value = {
            // The entry point said nothing: a channel-created session speaks for itself.
            "whatsapp,  -,        whatsapp",
            "sms,       -,        sms",
            "chat,      -,        chat",
            "web,       -,        app",
            "admin-app, -,        app",
            // The entry point that received the tap decides.
            "whatsapp,  app,      app",
            "chat,      whatsapp, whatsapp",
            "web,       chat,     chat",
            "chat,      sms,      sms",
            // Anything outside the vocabulary is the app.
            "web,       telegram, app"
    })
    void a_tap_reports_the_channel_it_came_from(String sessionLabel, String receivedOn, String expected) {
        Principal principal = new Principal(SUBJECT, HelloWorldProfileBootstrap.DEFAULT_TENANT, Set.of(),
                Map.of(), Instant.now());
        Ids.SessionId sid = engine.start(
                new StartConversationRequest(HelloWorldProfileBootstrap.DEFAULT_TENANT,
                        Optional.of(HelloWorldProfileBootstrap.HELLO_WORLD_PROFILE), sessionLabel, Map.of()),
                new AuthContext(HelloWorldProfileBootstrap.DEFAULT_TENANT, Map.of(), Optional.of(principal))
        ).session().id();
        String actionId = "ch-" + UUID.randomUUID();
        actions.put(new BoundActionStore.BoundAction(actionId, HelloWorldProfileBootstrap.DEFAULT_TENANT,
                BoundActionStore.Kind.TOOL, "Approve", null, HelloWorldProfileBootstrap.ECHO.value(),
                Map.of("text", "tap"), null, SUBJECT, null, Instant.now().plusSeconds(600), null, null));

        List<TurnEvent> events = new ArrayList<>();
        engine.send(sid,
                        new UserMessage("Approve", Map.of("_selection", Map.of("kind", "action", "actionId", actionId))),
                        new AuthContext(HelloWorldProfileBootstrap.DEFAULT_TENANT, Map.of(), Optional.empty(), receivedOn))
                .doOnNext(events::add)
                .blockLast();

        assertThat(events).filteredOn(e -> e instanceof TurnEvent.ToolResult r
                && r.status() == Turn.ToolResult.Status.OK).hasSize(1);
        assertThat(probe.channels).containsExactly(expected);
        assertThat(Set.of(TurnChannel.APP, TurnChannel.WHATSAPP, TurnChannel.SMS, TurnChannel.CHAT)).contains(expected);
    }

    public static class ChannelProbe implements ToolHandler<Map<String, Object>, Map<String, Object>> {
        final List<String> channels = new CopyOnWriteArrayList<>();

        @Override
        public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
            channels.add(String.valueOf(ctx.attributes().get(TurnChannel.ATTRIBUTE)));
            return Map.of("ok", true);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean("turnChannelProbe")
        ChannelProbe turnChannelProbe() {
            return new ChannelProbe();
        }
    }
}
