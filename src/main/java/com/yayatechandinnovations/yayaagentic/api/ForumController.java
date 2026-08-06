package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.ForumDtos;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointService;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.channel.chat.EntryPointStatus;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.ChatAccountService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.DevicePassService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.VerifiedDevice;
import com.yayatechandinnovations.yayaagentic.forum.ForumService;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The public forum surface ({@code chat-experience-design.md} §8): reading
 * follows the entry point's public visibility; posting/reacting/reporting
 * requires a verified account (the same L1 floor as chat media — identity is
 * the abuse brake). A disabled forum reads as empty-with-flag, not a 404, so
 * the app can show the right zero state.
 */
@RestController
@RequestMapping("/v1/chat/e/{handle}/forum")
public class ForumController {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration MINUTE = Duration.ofMinutes(1);

    // §8.2 ceilings.
    private static final int POSTS_PER_ACCOUNT_PER_HOUR = 6;
    private static final int REPLIES_PER_ACCOUNT_PER_HOUR = 20;
    private static final int REACTIONS_PER_ACCOUNT_PER_MINUTE = 30;
    private static final int REPORTS_PER_ACCOUNT_PER_HOUR = 10;

    private final ForumService forum;
    private final ChatEntryPointService entryPoints;
    private final DevicePassService devicePasses;
    private final ChatAccountService accounts;
    private final ChatRateLimiter rateLimiter;

    public ForumController(ForumService forum,
                           ChatEntryPointService entryPoints,
                           DevicePassService devicePasses,
                           ChatAccountService accounts,
                           ChatRateLimiter rateLimiter) {
        this.forum = forum;
        this.entryPoints = entryPoints;
        this.devicePasses = devicePasses;
        this.accounts = accounts;
        this.rateLimiter = rateLimiter;
    }

    /* ------------------------------- read ---------------------------------- */

    @GetMapping("/posts")
    public Mono<ForumDtos.FeedResponse> feed(@PathVariable("handle") String handle,
                                             @RequestParam(value = "cursor", required = false) String cursor,
                                             ServerWebExchange exchange) {
        return async(() -> {
            ChatEntryPoint ep = requirePublic(handle);
            if (!forum.isEnabled(ep.id().value())) {
                return new ForumDtos.FeedResponse(false, List.of(), null);
            }
            UUID caller = callerAccountId(exchange);
            ForumService.FeedPage page = forum.feed(ep.id().value(), cursor);
            return new ForumDtos.FeedResponse(true,
                    page.posts().stream().map(p -> ForumDtos.PostView.of(p, caller)).toList(),
                    page.nextCursor());
        });
    }

    @GetMapping("/posts/{postId}/replies")
    public Mono<ForumDtos.RepliesResponse> replies(@PathVariable("handle") String handle,
                                                   @PathVariable("postId") String postId,
                                                   ServerWebExchange exchange) {
        return async(() -> {
            ChatEntryPoint ep = requireEnabled(handle);
            UUID caller = callerAccountId(exchange);
            return new ForumDtos.RepliesResponse(forum.replies(ep.id().value(), postId).stream()
                    .map(p -> ForumDtos.PostView.of(p, caller)).toList());
        });
    }

    /* ------------------------------- write --------------------------------- */

    @PostMapping("/posts")
    public Mono<ForumDtos.PostView> create(@PathVariable("handle") String handle,
                                           @RequestBody ForumDtos.CreatePostRequest body,
                                           ServerWebExchange exchange) {
        return async(() -> {
            ChatEntryPoint ep = requireEnabled(handle);
            Caller caller = requireVerified(exchange);
            boolean isReply = body != null && body.replyTo() != null && !body.replyTo().isBlank();
            requireWithinLimit(rateLimiter.hit(
                    isReply ? "forum-reply-acct" : "forum-post-acct",
                    caller.accountId().toString(),
                    isReply ? REPLIES_PER_ACCOUNT_PER_HOUR : POSTS_PER_ACCOUNT_PER_HOUR, HOUR));
            try {
                var created = forum.postAsUser(ep.tenant().value(), ep.id().value(),
                        caller.accountId(), caller.displayName(),
                        body == null ? null : body.body(),
                        isReply ? body.replyTo() : null);
                return ForumDtos.PostView.of(created, caller.accountId());
            } catch (ForumService.ForumException e) {
                throw toApi(e);
            }
        });
    }

    @PatchMapping("/posts/{postId}")
    public Mono<ForumDtos.PostView> edit(@PathVariable("handle") String handle,
                                         @PathVariable("postId") String postId,
                                         @RequestBody ForumDtos.EditPostRequest body,
                                         ServerWebExchange exchange) {
        return async(() -> {
            requireEnabled(handle);
            Caller caller = requireVerified(exchange);
            try {
                return ForumDtos.PostView.of(
                        forum.editOwn(postId, caller.accountId(), body == null ? null : body.body()),
                        caller.accountId());
            } catch (ForumService.ForumException e) {
                throw toApi(e);
            }
        });
    }

    @DeleteMapping("/posts/{postId}")
    public Mono<Void> delete(@PathVariable("handle") String handle,
                             @PathVariable("postId") String postId,
                             ServerWebExchange exchange) {
        return asyncVoid(() -> {
            requireEnabled(handle);
            Caller caller = requireVerified(exchange);
            try {
                forum.deleteOwn(postId, caller.accountId());
            } catch (ForumService.ForumException e) {
                throw toApi(e);
            }
        });
    }

    @PutMapping("/posts/{postId}/reactions/{emoji}")
    public Mono<ForumDtos.PostView> react(@PathVariable("handle") String handle,
                                          @PathVariable("postId") String postId,
                                          @PathVariable("emoji") String emoji,
                                          ServerWebExchange exchange) {
        return async(() -> {
            ChatEntryPoint ep = requireEnabled(handle);
            Caller caller = requireVerified(exchange);
            requireWithinLimit(rateLimiter.hit("forum-react-acct", caller.accountId().toString(),
                    REACTIONS_PER_ACCOUNT_PER_MINUTE, MINUTE));
            try {
                return ForumDtos.PostView.of(
                        forum.react(ep.id().value(), postId, caller.accountId(), emoji),
                        caller.accountId());
            } catch (ForumService.ForumException e) {
                throw toApi(e);
            }
        });
    }

    @DeleteMapping("/posts/{postId}/reactions/{emoji}")
    public Mono<ForumDtos.PostView> unreact(@PathVariable("handle") String handle,
                                            @PathVariable("postId") String postId,
                                            @PathVariable("emoji") String emoji,
                                            ServerWebExchange exchange) {
        return async(() -> {
            ChatEntryPoint ep = requireEnabled(handle);
            Caller caller = requireVerified(exchange);
            try {
                return ForumDtos.PostView.of(
                        forum.unreact(ep.id().value(), postId, caller.accountId(), emoji),
                        caller.accountId());
            } catch (ForumService.ForumException e) {
                throw toApi(e);
            }
        });
    }

    @PostMapping("/posts/{postId}/report")
    public Mono<Void> report(@PathVariable("handle") String handle,
                             @PathVariable("postId") String postId,
                             @RequestBody(required = false) ForumDtos.ReportRequest body,
                             ServerWebExchange exchange) {
        return asyncVoid(() -> {
            ChatEntryPoint ep = requireEnabled(handle);
            Caller caller = requireVerified(exchange);
            requireWithinLimit(rateLimiter.hit("forum-report-acct", caller.accountId().toString(),
                    REPORTS_PER_ACCOUNT_PER_HOUR, HOUR));
            try {
                forum.report(ep.id().value(), postId, caller.accountId(),
                        body == null ? null : body.reason());
            } catch (ForumService.ForumException e) {
                throw toApi(e);
            }
        });
    }

    /* ------------------------------ helpers --------------------------------- */

    private record Caller(UUID accountId, String displayName) {}

    private ChatEntryPoint requirePublic(String handle) {
        ChatEntryPoint ep = entryPoints.findByHandle(handle)
                .orElseThrow(ChatApiException::unknownHandle);
        if (ep.status() != EntryPointStatus.ACTIVE && ep.status() != EntryPointStatus.PAUSED) {
            throw ChatApiException.unknownHandle();
        }
        return ep;
    }

    private ChatEntryPoint requireEnabled(String handle) {
        ChatEntryPoint ep = requirePublic(handle);
        if (!forum.isEnabled(ep.id().value())) throw ChatApiException.unknownHandle();
        return ep;
    }

    /** The L1 floor (§8.1): posting needs a live, account-linked device pass —
     *  the display name is the account's, never client-supplied. */
    private Caller requireVerified(ServerWebExchange exchange) {
        VerifiedDevice device = devicePasses.verify(
                        exchange.getRequest().getHeaders().getFirst("X-Device-Pass"))
                .orElseThrow(ChatApiException::authRequired);
        ChatAccountEntity account = accounts.liveAccountFor(device)
                .orElseThrow(ChatApiException::authRequired);
        String name = account.getName();
        return new Caller(account.getId(), name == null || name.isBlank() ? "Member" : name);
    }

    private UUID callerAccountId(ServerWebExchange exchange) {
        return devicePasses.verify(exchange.getRequest().getHeaders().getFirst("X-Device-Pass"))
                .flatMap(accounts::liveAccountFor)
                .map(ChatAccountEntity::getId)
                .orElse(null);
    }

    private void requireWithinLimit(ChatRateLimiter.Decision d) {
        if (!d.allowed()) throw ChatApiException.rateLimited(d.retryAfterSeconds());
    }

    private static ChatApiException toApi(ForumService.ForumException e) {
        HttpStatus status = switch (e.code()) {
            case "not_found" -> HttpStatus.NOT_FOUND;
            case "edit_window_closed" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return new ChatApiException(status, e.code(), e.getMessage(), 0);
    }

    private static <T> Mono<T> async(java.util.function.Supplier<T> body) {
        return Mono.fromSupplier(body).subscribeOn(Schedulers.boundedElastic());
    }

    private static Mono<Void> asyncVoid(Runnable body) {
        return Mono.<Void>fromRunnable(body).subscribeOn(Schedulers.boundedElastic());
    }
}
