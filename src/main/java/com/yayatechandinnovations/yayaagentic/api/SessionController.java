package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.SessionDtos;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.StartSessionResult;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/v1/sessions")
public class SessionController {

    private final ConversationEngine engine;

    public SessionController(ConversationEngine engine) {
        this.engine = engine;
    }

    @PostMapping
    public Mono<SessionDtos.StartSessionResponse> start(@RequestBody SessionDtos.StartSessionRequest body,
                                                        ServerWebExchange exchange) {
        Ids.TenantId tenant = new Ids.TenantId(body.tenant() == null ? "default" : body.tenant());
        Optional<Ids.ProfileId> explicit = body.profileId() == null
                ? Optional.empty()
                : Optional.of(new Ids.ProfileId(body.profileId(),
                        body.profileVersion() == null ? 1 : body.profileVersion()));

        StartConversationRequest req = new StartConversationRequest(tenant, explicit,
                body.channel() == null ? "web" : body.channel(),
                body.hints() == null ? Map.of() : body.hints());

        AuthContext auth = authContext(tenant, exchange);
        StartSessionResult result = engine.start(req, auth);
        return Mono.just(SessionDtos.StartSessionResponse.of(
                result.session().id(), result.profile(), result.greeting(), result.quickReplies()));
    }

    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> sendMessage(@PathVariable("id") String id,
                                                     @RequestBody SessionDtos.MessageRequest body,
                                                     ServerWebExchange exchange) {
        Ids.SessionId sessionId = new Ids.SessionId(id);
        AuthContext auth = authContext(new Ids.TenantId("default"), exchange);
        return engine.send(sessionId, new UserMessage(body.text(), Map.of()), auth)
                .map(SessionController::toSse);
    }

    @PostMapping("/{id}/end")
    public Mono<Void> end(@PathVariable("id") String id, ServerWebExchange exchange) {
        engine.end(new Ids.SessionId(id), authContext(new Ids.TenantId("default"), exchange));
        return Mono.empty();
    }

    private AuthContext authContext(Ids.TenantId tenant, ServerWebExchange exchange) {
        Map<String, String> headers = exchange.getRequest().getHeaders().toSingleValueMap();
        return new AuthContext(tenant, headers, Optional.empty());
    }

    private static ServerSentEvent<Object> toSse(TurnEvent event) {
        String name = switch (event) {
            case TurnEvent.Token t -> "token";
            case TurnEvent.ToolCall t -> "tool_call";
            case TurnEvent.ToolResult t -> "tool_result";
            case TurnEvent.Citation c -> "citation";
            case TurnEvent.UiHint u -> "ui_hint";
            case TurnEvent.End e -> "end";
        };
        return ServerSentEvent.builder((Object) event).event(name).build();
    }
}
