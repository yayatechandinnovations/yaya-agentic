package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointService;
import com.yayatechandinnovations.yayaagentic.channel.chat.EntryPointStatus;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.outreach.ChatInboxService;
import com.yayatechandinnovations.yayaagentic.outreach.ChatInboxService.InboxItem;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;

/**
 * The operator-facing drain for system messages delivered to chat ({@code
 * system-messaging-design.md} §11). Lives under the public {@code /v1/chat}
 * front door but requires a verified (non-anonymous) identity: a recipient may
 * only ever see their own inbox — the recipient key is derived from the verified
 * subject, never from a query parameter.
 *
 * <p>SM-0 returns pending items as JSON and marks them delivered. SSE streaming
 * (drain as {@code TurnEvent}s) + a push wake nudge land in SM-3.
 */
@RestController
@RequestMapping("/v1/chat/e/{handle}/inbox")
public class ChatInboxController {

    private final ChatEntryPointService entryPoints;
    private final Authenticator authenticator;
    private final ChatInboxService inbox;

    public ChatInboxController(ChatEntryPointService entryPoints,
                               Authenticator authenticator,
                               ChatInboxService inbox) {
        this.entryPoints = entryPoints;
        this.authenticator = authenticator;
        this.inbox = inbox;
    }

    @GetMapping
    public Mono<InboxResponse> drain(@PathVariable("handle") String handle,
                                     @RequestParam(value = "peek", defaultValue = "false") boolean peek,
                                     ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            ChatEntryPoint ep = requirePublic(handle);
            Principal principal = requireVerified(ep, exchange);
            List<InboxItem> items = inbox.drain(ep.tenant(), ep.id().value(),
                    principal.subject(), /* markDelivered */ !peek);
            return new InboxResponse(items);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Unread count (owner-cockpit design §2.3): a pure peek — never marks
     * delivered — so clients can badge without consuming the inbox.
     */
    @GetMapping("/count")
    public Mono<CountResponse> count(@PathVariable("handle") String handle,
                                     ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            ChatEntryPoint ep = requirePublic(handle);
            Principal principal = requireVerified(ep, exchange);
            List<InboxItem> items = inbox.drain(ep.tenant(), ep.id().value(),
                    principal.subject(), /* markDelivered */ false);
            return new CountResponse(items.size());
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

    /** The inbox is identity-scoped: an anonymous or missing bearer can't drain. */
    private Principal requireVerified(ChatEntryPoint ep, ServerWebExchange exchange) {
        AuthContext ctx = new AuthContext(ep.tenant(),
                exchange.getRequest().getHeaders().toSingleValueMap(), Optional.empty());
        return authenticator.tryAuthenticate(ctx)
                .filter(p -> !isAnonymous(p))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "sign-in required to read the inbox"));
    }

    private static boolean isAnonymous(Principal p) {
        String s = p.subject();
        return s == null || s.startsWith("anonymous-") || s.contains(":anon:");
    }

    public record InboxResponse(List<InboxItem> items) {}

    public record CountResponse(int count) {}
}
