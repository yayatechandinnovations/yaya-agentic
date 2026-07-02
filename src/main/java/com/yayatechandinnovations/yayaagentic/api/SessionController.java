package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.SessionDtos;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.playground.ActAs;
import com.yayatechandinnovations.yayaagentic.auth.playground.ActAsMaterializer;
import com.yayatechandinnovations.yayaagentic.auth.playground.PlaygroundActAsRegistry;
import com.yayatechandinnovations.yayaagentic.channel.websse.WebSseChannelAdapter;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.ActionNotPermittedException;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.StartSessionResult;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import com.yayatechandinnovations.yayaagentic.persistence.SessionRepository;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import com.yayatechandinnovations.yayaagentic.tenant.OriginEnforcer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/v1/sessions")
public class SessionController {

    private final ConversationEngine engine;
    private final OriginEnforcer originEnforcer;
    private final SessionRepository sessions;
    private final ActAsMaterializer actAsMaterializer;
    private final PlaygroundActAsRegistry actAsRegistry;
    private final WebSseChannelAdapter webSse;

    public SessionController(ConversationEngine engine,
                             OriginEnforcer originEnforcer,
                             SessionRepository sessions,
                             ActAsMaterializer actAsMaterializer,
                             PlaygroundActAsRegistry actAsRegistry,
                             WebSseChannelAdapter webSse) {
        this.engine = engine;
        this.originEnforcer = originEnforcer;
        this.sessions = sessions;
        this.actAsMaterializer = actAsMaterializer;
        this.actAsRegistry = actAsRegistry;
        this.webSse = webSse;
    }

    @PostMapping
    public Mono<SessionDtos.StartSessionResponse> start(@RequestBody SessionDtos.StartSessionRequest body,
                                                        ServerWebExchange exchange) {
        Ids.TenantId tenant = new Ids.TenantId(body.tenant() == null ? "default" : body.tenant());
        return enforceOriginMono(tenant.value(), exchange).then(Mono.fromSupplier(() -> {
            Optional<Ids.ProfileId> explicit = body.profileId() == null
                    ? Optional.empty()
                    : Optional.of(new Ids.ProfileId(body.profileId(),
                            body.profileVersion() == null ? 1 : body.profileVersion()));

            StartConversationRequest req = new StartConversationRequest(tenant, explicit,
                    body.channel() == null ? "web" : body.channel(),
                    body.hints() == null ? Map.of() : body.hints());

            AuthContext auth = authContext(tenant, exchange);
            Optional<ActAs> actAs = Optional.ofNullable(body.actAs());
            auth = actAsMaterializer.applyIfPresent(auth, actAs);
            StartSessionResult result = engine.start(req, auth);
            actAs.ifPresent(spec -> actAsRegistry.put(result.session().id(), spec));
            return SessionDtos.StartSessionResponse.of(
                    result.session().id(), result.profile(), result.greeting(), result.quickReplies());
        }));
    }

    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> sendMessage(@PathVariable("id") String id,
                                                     @RequestBody SessionDtos.MessageRequest body,
                                                     ServerWebExchange exchange) {
        Ids.SessionId sessionId = new Ids.SessionId(id);
        String tenant = lookupTenant(id);
        return enforceOriginMono(tenant, exchange).thenMany(Flux.defer(() -> {
            AuthContext auth = authContext(new Ids.TenantId(tenant), exchange);
            auth = actAsMaterializer.applyIfPresent(auth, actAsRegistry.get(sessionId));
            return engine.send(sessionId, new UserMessage(body.text(), Map.of()), auth)
                    .map(webSse::toSse);
        }));
    }

    /**
     * Client-initiated action (a tap on the cart, "Load more", "Checkout"): runs
     * one whitelisted tool through the same executor + Authorizer as a chat
     * message, streaming back the same SSE shape (ToolResult + cart UiHint). A
     * refused action (tool not in profile / not clientInvocable) maps to 403 with
     * no body — the tool's existence is never leaked. See ecommerce-chat-design §9.
     */
    @PostMapping(value = "/{id}/actions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> action(@PathVariable("id") String id,
                                                @RequestBody SessionDtos.ActionRequest body,
                                                ServerWebExchange exchange) {
        Ids.SessionId sessionId = new Ids.SessionId(id);
        String tenant = lookupTenant(id);
        return enforceOriginMono(tenant, exchange).thenMany(Flux.defer(() -> {
            AuthContext auth = authContext(new Ids.TenantId(tenant), exchange);
            auth = actAsMaterializer.applyIfPresent(auth, actAsRegistry.get(sessionId));
            return engine.dispatchAction(sessionId, new Ids.ToolId(body.tool()),
                            body.args() == null ? Map.of() : body.args(), auth)
                    .map(webSse::toSse);
        })).onErrorMap(ActionNotPermittedException.class,
                e -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    }

    @PostMapping("/{id}/end")
    public Mono<Void> end(@PathVariable("id") String id, ServerWebExchange exchange) {
        String tenant = lookupTenant(id);
        Ids.SessionId sessionId = new Ids.SessionId(id);
        return enforceOriginMono(tenant, exchange).then(Mono.fromRunnable(() -> {
            AuthContext auth = authContext(new Ids.TenantId(tenant), exchange);
            auth = actAsMaterializer.applyIfPresent(auth, actAsRegistry.get(sessionId));
            engine.end(sessionId, auth);
            actAsRegistry.remove(sessionId);
        }));
    }

    private Mono<Void> enforceOriginMono(String tenantId, ServerWebExchange exchange) {
        String origin = exchange.getRequest().getHeaders().getFirst("Origin");
        String path = exchange.getRequest().getPath().value();
        return Mono.fromRunnable(() ->
                originEnforcer.requirePermitted(tenantId, origin, path, null));
    }

    private String lookupTenant(String sessionId) {
        try {
            return sessions.findById(UUID.fromString(sessionId))
                    .map(s -> s.getTenantId())
                    .orElse("default");
        } catch (IllegalArgumentException malformed) {
            return "default";
        }
    }

    private AuthContext authContext(Ids.TenantId tenant, ServerWebExchange exchange) {
        Map<String, String> headers = exchange.getRequest().getHeaders().toSingleValueMap();
        return new AuthContext(tenant, headers, Optional.empty());
    }
}
