package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.adminevents.AdminChangeFeed;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceAccessHelper;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

import java.time.Duration;

/**
 * {@code GET /v1/admin/events?tenant=…} — the admin console's change feed
 * (admin-console-ux-design.md §4 A3). VIEWER access to the tenant, the same
 * bar as its list endpoints, because the feed only says what those lists
 * would already show changed.
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminEventsController {

    static final Duration HEARTBEAT = Duration.ofSeconds(25);

    /**
     * Streams end after this long and the browser reconnects with its
     * Last-Event-ID. The access check runs per connection, so a revoked
     * membership or expired operator session stops receiving changes within
     * this bound.
     */
    static final Duration MAX_CONNECTION = Duration.ofMinutes(30);

    private final AdminChangeFeed feed;
    private final WorkspaceAccessHelper workspaceAccess;

    public AdminEventsController(AdminChangeFeed feed, WorkspaceAccessHelper workspaceAccess) {
        this.feed = feed;
        this.workspaceAccess = workspaceAccess;
    }

    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> events(@RequestParam(required = false) String tenant,
                                                @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
                                                @RequestParam(value = "lastEventId", required = false) String lastEventIdParam,
                                                ServerWebExchange exchange) {
        // The browser sends the header on its own reconnects; the console passes
        // the param when it opens a fresh connection after the old one closed.
        String lastEventId = lastEventIdHeader != null ? lastEventIdHeader : lastEventIdParam;
        String resolved = workspaceAccess.require(tenant, exchange, Role.VIEWER);
        exchange.getResponse().getHeaders().set("X-Accel-Buffering", "no");
        exchange.getResponse().getHeaders().setCacheControl("no-cache");

        Flux<ServerSentEvent<Object>> hello = Flux.just(ServerSentEvent.builder()
                .event("hello").retry(Duration.ofSeconds(3)).data((Object) java.util.Map.of("feed", feed.bootId())).build());
        Flux<ServerSentEvent<Object>> changes = feed.stream(resolved, lastEventId).map(frame -> switch (frame) {
            case AdminChangeFeed.Frame.Change c -> ServerSentEvent.builder()
                    .id(c.change().eventId()).event("resource-changed").data((Object) c.change()).build();
            case AdminChangeFeed.Frame.Reset r -> ServerSentEvent.builder()
                    .event("reset").data((Object) java.util.Map.of()).build();
        });
        Flux<ServerSentEvent<Object>> heartbeat = Flux.interval(HEARTBEAT)
                .map(i -> ServerSentEvent.builder().comment("ping").build());

        return hello.concatWith(Flux.merge(changes, heartbeat)).take(MAX_CONNECTION);
    }
}
