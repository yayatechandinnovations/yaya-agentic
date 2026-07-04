package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.ChatAccountDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.PublicChatDtos;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointService;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatSessionLinkService;
import com.yayatechandinnovations.yayaagentic.channel.chat.EntryPointStatus;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.ChatAccountService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.ChatPhoneChangeService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.DevicePassService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.PhoneVerifier;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.VerifiedDevice;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountTileEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountTileRepository;
import com.yayatechandinnovations.yayaagentic.persistence.SessionEntity;
import com.yayatechandinnovations.yayaagentic.recorder.ConversationRecorder;
import com.yayatechandinnovations.yayaagentic.recorder.Operations;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * End-user account self-service ({@code chat-account-self-service-design.md} §6, §8):
 * view/rename, the dual-OTP phone change, delete, sign-out-everywhere, and the
 * server-side chat directory. Every route rides behind a valid device pass — the
 * caller is always the account holder — and a revoked pass (generation mismatch)
 * is rejected exactly as it is on the public chat door (§5.2).
 */
@RestController
@RequestMapping("/v1/chat/account")
public class ChatAccountController {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{6,17}$");
    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DAY = Duration.ofHours(24);

    // §20.4 ceilings for the delete-confirmation OTP (this route sends an SMS).
    private static final int START_PER_IP_PER_HOUR = 5;
    private static final int START_PER_PHONE_PER_DAY = 5;
    private static final int START_GLOBAL_PER_DAY = 5000;
    private static final int CHECK_PER_PHONE_PER_HOUR = 10;

    private final DevicePassService devicePasses;
    private final ChatAccountService accounts;
    private final ChatPhoneChangeService phoneChanges;
    private final PhoneVerifier verifier;
    private final ChatRateLimiter rateLimiter;
    private final ChatAccountTileRepository tiles;
    private final ChatEntryPointService entryPoints;
    private final ChatSessionLinkService sessionLinks;
    private final ConversationRecorder recorder;

    public ChatAccountController(DevicePassService devicePasses,
                                 ChatAccountService accounts,
                                 ChatPhoneChangeService phoneChanges,
                                 PhoneVerifier verifier,
                                 ChatRateLimiter rateLimiter,
                                 ChatAccountTileRepository tiles,
                                 ChatEntryPointService entryPoints,
                                 ChatSessionLinkService sessionLinks,
                                 ConversationRecorder recorder) {
        this.devicePasses = devicePasses;
        this.accounts = accounts;
        this.phoneChanges = phoneChanges;
        this.verifier = verifier;
        this.rateLimiter = rateLimiter;
        this.tiles = tiles;
        this.entryPoints = entryPoints;
        this.sessionLinks = sessionLinks;
        this.recorder = recorder;
    }

    /* ------------------------------ view ---------------------------------- */

    @GetMapping
    public Mono<ChatAccountDtos.AccountView> view(ServerWebExchange exchange) {
        return async(() -> {
            ChatAccountEntity a = require(exchange).account();
            return new ChatAccountDtos.AccountView(a.getPhoneE164(), a.getName(),
                    a.getCreatedAt() == null ? null : a.getCreatedAt().toString());
        });
    }

    /* ------------------------------ rename (§6.1) ------------------------- */

    @PatchMapping
    public Mono<ChatAccountDtos.RenameResponse> rename(@RequestBody ChatAccountDtos.RenameRequest body,
                                                       ServerWebExchange exchange) {
        return async(() -> {
            Holder h = require(exchange);
            ChatAccountService.VerifiedAccount acct =
                    accounts.rename(h.account().getId(), body == null ? null : body.name());
            return new ChatAccountDtos.RenameResponse(reissue(h.device(), acct), acct.name());
        });
    }

    /* -------------------------- phone change (§6.2) ----------------------- */

    @PostMapping("/phone-change/start")
    public Mono<ChatAccountDtos.PhoneChangeStartResponse> phoneChangeStart(
            @RequestBody ChatAccountDtos.PhoneChangeStartRequest body, ServerWebExchange exchange) {
        return async(() -> {
            ChatAccountEntity a = require(exchange).account();
            String newPhone = requireE164(body == null ? null : body.newPhoneE164());
            var change = phoneChanges.start(a.getId(), a.getPhoneE164(), newPhone, clientIp(exchange));
            return new ChatAccountDtos.PhoneChangeStartResponse(change.getId().toString(), change.getState());
        });
    }

    @PostMapping("/phone-change/verify-old")
    public Mono<ChatAccountDtos.PhoneChangeStateResponse> phoneChangeVerifyOld(
            @RequestBody ChatAccountDtos.PhoneChangeCodeRequest body, ServerWebExchange exchange) {
        return async(() -> {
            ChatAccountEntity a = require(exchange).account();
            phoneChanges.verifyOld(a.getId(), changeId(body), code(body), clientIp(exchange));
            return new ChatAccountDtos.PhoneChangeStateResponse("NEW_PENDING");
        });
    }

    @PostMapping("/phone-change/verify-new")
    public Mono<ChatAccountDtos.PhoneChangeCompleteResponse> phoneChangeVerifyNew(
            @RequestBody ChatAccountDtos.PhoneChangeCodeRequest body, ServerWebExchange exchange) {
        return async(() -> {
            Holder h = require(exchange);
            ChatAccountService.VerifiedAccount acct =
                    phoneChanges.verifyNew(h.account().getId(), changeId(body), code(body));
            return new ChatAccountDtos.PhoneChangeCompleteResponse(reissue(h.device(), acct), acct.phoneE164());
        });
    }

    @PostMapping("/phone-change/resend")
    public Mono<Void> phoneChangeResend(@RequestBody ChatAccountDtos.PhoneChangeCodeRequest body,
                                        ServerWebExchange exchange) {
        return asyncVoid(() -> {
            ChatAccountEntity a = require(exchange).account();
            phoneChanges.resend(a.getId(), changeId(body), clientIp(exchange));
        });
    }

    @PostMapping("/phone-change/abort")
    public Mono<Void> phoneChangeAbort(@RequestBody ChatAccountDtos.PhoneChangeCodeRequest body,
                                       ServerWebExchange exchange) {
        return asyncVoid(() -> {
            ChatAccountEntity a = require(exchange).account();
            phoneChanges.abort(a.getId(), changeId(body));
        });
    }

    /* ------------------------------ delete (§6.3) ------------------------- */

    @PostMapping("/delete/start")
    public Mono<ChatAccountDtos.StateResponse> deleteStart(ServerWebExchange exchange) {
        return async(() -> {
            ChatAccountEntity a = require(exchange).account();
            requireWithinLimit(rateLimiter.hit("verify-global", "all", START_GLOBAL_PER_DAY, DAY));
            requireWithinLimit(rateLimiter.hit("verify-start-ip", clientIp(exchange), START_PER_IP_PER_HOUR, HOUR));
            requireWithinLimit(rateLimiter.hit("verify-start-phone", a.getPhoneE164(), START_PER_PHONE_PER_DAY, DAY));
            if (!verifier.start(a.getPhoneE164()).ok()) {
                throw new ChatApiException(HttpStatus.BAD_GATEWAY, "verify_failed",
                        "could not send a verification code", 0);
            }
            return new ChatAccountDtos.StateResponse("PENDING");
        });
    }

    @PostMapping("/delete/confirm")
    public Mono<Void> deleteConfirm(@RequestBody ChatAccountDtos.DeleteConfirmRequest body,
                                    ServerWebExchange exchange) {
        return asyncVoid(() -> {
            ChatAccountEntity a = require(exchange).account();
            if (body == null || body.code() == null || body.code().isBlank()) {
                throw new ChatApiException(HttpStatus.BAD_REQUEST, "bad_request", "code required", 0);
            }
            requireWithinLimit(rateLimiter.hit("verify-check-phone", a.getPhoneE164(), CHECK_PER_PHONE_PER_HOUR, HOUR));
            if (!verifier.check(a.getPhoneE164(), body.code().trim()).approved()) {
                throw new ChatApiException(HttpStatus.UNAUTHORIZED, "verify_denied",
                        "that code is invalid or expired", 0);
            }
            // Erase conversations through the recorder (never direct SQL — design
            // §5.9), then scrub the account + revoke all passes + drop tiles.
            String subject = "chat:" + a.getPhoneE164();
            Operations.DeletionRequest del = new Operations.DeletionRequest(subject, "end-user account delete");
            for (SessionEntity s : sessionLinks.sessionsForAccount(a.getId())) {
                recorder.deleteSession(new Ids.SessionId(s.getId().toString()), del);
            }
            tiles.deleteByAccountId(a.getId());
            accounts.softDelete(a.getId());
        });
    }

    @PostMapping("/sign-out-everywhere")
    public Mono<Void> signOutEverywhere(ServerWebExchange exchange) {
        return asyncVoid(() -> accounts.signOutEverywhere(require(exchange).account().getId()));
    }

    /* ------------------------------ tiles (§7.1) -------------------------- */

    @GetMapping("/tiles")
    public Mono<ChatAccountDtos.TilesResponse> listTiles(ServerWebExchange exchange) {
        return async(() -> {
            ChatAccountEntity a = require(exchange).account();
            List<PublicChatDtos.EntryPointCard> cards = tiles.findByAccountId(a.getId()).stream()
                    .map(t -> entryPoints.findByHandle(t.getHandle()).orElse(null))
                    .filter(ep -> ep != null && servesPublic(ep))
                    .map(PublicChatDtos.EntryPointCard::of)
                    .toList();
            return new ChatAccountDtos.TilesResponse(cards);
        });
    }

    @PutMapping("/tiles/{handle}")
    public Mono<Void> addTile(@PathVariable("handle") String handle, ServerWebExchange exchange) {
        return asyncVoid(() -> {
            ChatAccountEntity a = require(exchange).account();
            ChatEntryPoint ep = requirePublic(handle);
            tiles.save(new ChatAccountTileEntity(a.getId(), ep.id().value(), handle));
        });
    }

    @DeleteMapping("/tiles/{handle}")
    public Mono<Void> removeTile(@PathVariable("handle") String handle, ServerWebExchange exchange) {
        return asyncVoid(() -> {
            ChatAccountEntity a = require(exchange).account();
            entryPoints.findByHandle(handle).ifPresent(ep ->
                    tiles.deleteById(new ChatAccountTileEntity.Key(a.getId(), ep.id().value())));
        });
    }

    /* ------------------------------ helpers ------------------------------- */

    private record Holder(VerifiedDevice device, ChatAccountEntity account) {}

    /** Resolve the caller's live account from the device pass, or 401. A revoked
     *  pass (generation mismatch / deleted account) fails here (§5.2). */
    private Holder require(ServerWebExchange exchange) {
        VerifiedDevice d = devicePasses.verify(
                        exchange.getRequest().getHeaders().getFirst("X-Device-Pass"))
                .orElseThrow(ChatApiException::authRequired);
        ChatAccountEntity a = accounts.liveAccountFor(d).orElseThrow(ChatApiException::authRequired);
        return new Holder(d, a);
    }

    private String reissue(VerifiedDevice device, ChatAccountService.VerifiedAccount acct) {
        return devicePasses.issue(new VerifiedDevice(
                acct.phoneE164(), acct.name(), device.deviceId(), acct.id(), acct.passGen()));
    }

    private ChatEntryPoint requirePublic(String handle) {
        ChatEntryPoint ep = entryPoints.findByHandle(handle).orElseThrow(ChatApiException::unknownHandle);
        if (!servesPublic(ep)) throw ChatApiException.unknownHandle();
        return ep;
    }

    private static boolean servesPublic(ChatEntryPoint ep) {
        return ep.status() == EntryPointStatus.ACTIVE || ep.status() == EntryPointStatus.PAUSED;
    }

    private static UUID changeId(ChatAccountDtos.PhoneChangeCodeRequest body) {
        try {
            return UUID.fromString(body.changeId());
        } catch (RuntimeException e) {
            throw new ChatApiException(HttpStatus.NOT_FOUND, "not_found", "no such phone change", 0);
        }
    }

    private static String code(ChatAccountDtos.PhoneChangeCodeRequest body) {
        return body == null ? null : body.code();
    }

    private static String requireE164(String phone) {
        if (phone == null || !E164.matcher(phone.trim()).matches()) {
            throw new ChatApiException(HttpStatus.BAD_REQUEST, "bad_request",
                    "phoneE164 must be E.164 (+ then 7–18 digits)", 0);
        }
        return phone.trim();
    }

    private void requireWithinLimit(ChatRateLimiter.Decision d) {
        if (!d.allowed()) throw ChatApiException.rateLimited(d.retryAfterSeconds());
    }

    private static String clientIp(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return exchange.getRequest().getRemoteAddress() == null
                ? "unknown"
                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }

    private static <T> Mono<T> async(java.util.function.Supplier<T> body) {
        return Mono.fromSupplier(body).subscribeOn(Schedulers.boundedElastic());
    }

    private static Mono<Void> asyncVoid(Runnable body) {
        return Mono.<Void>fromRunnable(body).subscribeOn(Schedulers.boundedElastic());
    }
}
