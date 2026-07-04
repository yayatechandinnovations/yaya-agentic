package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointService;
import com.yayatechandinnovations.yayaagentic.channel.chat.EntryPointStatus;
import com.yayatechandinnovations.yayaagentic.channel.system.OutreachRecipient;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.ChatPushTokenEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatPushTokenRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;

/**
 * Device registration for chat wake nudges (§11 #3, SM-3). Identity-scoped: a
 * token is bound to the verified recipient's key, so a device can only register
 * itself for its own inbox.
 */
@RestController
@RequestMapping("/v1/chat/e/{handle}/push-tokens")
public class ChatPushTokenController {

    private final ChatEntryPointService entryPoints;
    private final Authenticator authenticator;
    private final ChatPushTokenRepository tokens;

    public ChatPushTokenController(ChatEntryPointService entryPoints,
                                   Authenticator authenticator,
                                   ChatPushTokenRepository tokens) {
        this.entryPoints = entryPoints;
        this.authenticator = authenticator;
        this.tokens = tokens;
    }

    @PostMapping
    public Mono<RegisterResponse> register(@PathVariable("handle") String handle,
                                           @RequestBody RegisterRequest body,
                                           ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            ChatEntryPoint ep = requirePublic(handle);
            Principal op = requireVerified(ep, exchange);
            if (body == null || body.token() == null || body.token().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "token required");
            }
            String recipientKey = new OutreachRecipient.ByPrincipal(op.subject()).key();
            String platform = body.platform() == null || body.platform().isBlank()
                    ? "WEBPUSH" : body.platform().trim().toUpperCase();
            if (tokens.findByRecipientKeyAndToken(recipientKey, body.token().trim()).isEmpty()) {
                try {
                    tokens.save(new ChatPushTokenEntity(ep.tenant().value(), ep.id().value(),
                            recipientKey, platform, body.token().trim()));
                } catch (DataIntegrityViolationException ignored) {
                    // Lost the register race — already present, idempotent.
                }
            }
            return new RegisterResponse(true);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private ChatEntryPoint requirePublic(String handle) {
        ChatEntryPoint ep = entryPoints.findByHandle(handle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown handle"));
        if (ep.status() != EntryPointStatus.ACTIVE && ep.status() != EntryPointStatus.PAUSED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown handle");
        }
        return ep;
    }

    private Principal requireVerified(ChatEntryPoint ep, ServerWebExchange exchange) {
        AuthContext ctx = new AuthContext(ep.tenant(),
                exchange.getRequest().getHeaders().toSingleValueMap(), Optional.empty());
        return authenticator.tryAuthenticate(ctx)
                .filter(p -> p.subject() != null && !p.subject().startsWith("anonymous-")
                        && !p.subject().contains(":anon:"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign-in required"));
    }

    public record RegisterRequest(String platform, String token) {}

    public record RegisterResponse(boolean registered) {}
}
