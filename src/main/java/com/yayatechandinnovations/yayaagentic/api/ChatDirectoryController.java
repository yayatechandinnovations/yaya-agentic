package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.DirectoryDtos.DirectoryPage;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatDirectoryService;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Public, end-user-facing chat directory ({@code chat-public-directory-design.md} §4).
 * Sibling to {@link PublicChatController} under the same public {@code /v1/chat} prefix.
 * The directory is intentionally enumerable — but only over entry points an operator has
 * opted into ({@code ACTIVE + discoverable}); everything else keeps the §6.1
 * non-enumeration guarantee. Metered per client IP because it is internet-facing.
 */
@RestController
@RequestMapping("/v1/chat")
public class ChatDirectoryController {

    // A directory sweep is cheap (one bounded, index-backed page), so the cap is generous;
    // it exists to bound scraping throughput, not to gate normal browsing.
    private static final int SEARCHES_PER_IP_PER_MINUTE = 120;
    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final ChatDirectoryService directory;
    private final ChatRateLimiter rateLimiter;

    public ChatDirectoryController(ChatDirectoryService directory, ChatRateLimiter rateLimiter) {
        this.directory = directory;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/directory")
    public Mono<DirectoryPage> search(@RequestParam(value = "q", required = false) String q,
                                      @RequestParam(value = "cursor", required = false) String cursor,
                                      @RequestParam(value = "limit", required = false) Integer limit,
                                      ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            ChatRateLimiter.Decision d = rateLimiter.hit("dir", clientIp(exchange),
                    SEARCHES_PER_IP_PER_MINUTE, MINUTE);
            if (!d.allowed()) throw ChatApiException.rateLimited(d.retryAfterSeconds());
            return directory.search(q, cursor, limit);
        });
    }

    private static String clientIp(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return exchange.getRequest().getRemoteAddress() == null
                ? "unknown"
                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }
}
