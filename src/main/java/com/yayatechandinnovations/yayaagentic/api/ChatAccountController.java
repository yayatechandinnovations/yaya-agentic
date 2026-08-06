package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.ChatAccountDtos;
import com.yayatechandinnovations.yayaagentic.api.dto.PublicChatDtos;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointService;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatSessionLinkService;
import com.yayatechandinnovations.yayaagentic.channel.chat.EntryPointStatus;
import com.yayatechandinnovations.yayaagentic.channel.chat.media.ChatAttachmentService;
import com.yayatechandinnovations.yayaagentic.channel.chat.receipts.ChatReceiptService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.ChatAccountService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.ChatDeviceService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.ChatPhoneChangeService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.ChatPinService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.ChatVerifyLimitsProperties;
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

    // §20.4 ceilings for the delete-confirmation OTP (this route sends an SMS);
    // shared, env-tunable settings with the verify surface.
    private final ChatVerifyLimitsProperties limits;
    private final DevicePassService devicePasses;
    private final ChatAccountService accounts;
    private final ChatPhoneChangeService phoneChanges;
    private final PhoneVerifier verifier;
    private final ChatRateLimiter rateLimiter;
    private final ChatAccountTileRepository tiles;
    private final ChatEntryPointService entryPoints;
    private final ChatSessionLinkService sessionLinks;
    private final ConversationRecorder recorder;
    private final ChatDeviceService devices;
    private final ChatPinService pins;
    private final ChatAttachmentService chatAttachments;
    private final ChatReceiptService chatReceipts;

    public ChatAccountController(DevicePassService devicePasses,
                                 ChatAccountService accounts,
                                 ChatPhoneChangeService phoneChanges,
                                 PhoneVerifier verifier,
                                 ChatRateLimiter rateLimiter,
                                 ChatAccountTileRepository tiles,
                                 ChatEntryPointService entryPoints,
                                 ChatSessionLinkService sessionLinks,
                                 ConversationRecorder recorder,
                                 ChatDeviceService devices,
                                 ChatPinService pins,
                                 ChatAttachmentService chatAttachments,
                                 ChatReceiptService chatReceipts,
                                 ChatVerifyLimitsProperties limits) {
        this.limits = limits;
        this.devicePasses = devicePasses;
        this.accounts = accounts;
        this.phoneChanges = phoneChanges;
        this.verifier = verifier;
        this.rateLimiter = rateLimiter;
        this.tiles = tiles;
        this.entryPoints = entryPoints;
        this.sessionLinks = sessionLinks;
        this.recorder = recorder;
        this.devices = devices;
        this.pins = pins;
        this.chatAttachments = chatAttachments;
        this.chatReceipts = chatReceipts;
    }

    /* ------------------------------ view ---------------------------------- */

    @GetMapping
    public Mono<ChatAccountDtos.AccountView> view(ServerWebExchange exchange) {
        return async(() -> {
            ChatAccountEntity a = require(exchange).account();
            return new ChatAccountDtos.AccountView(a.getPhoneE164(), a.getName(),
                    a.getCreatedAt() == null ? null : a.getCreatedAt().toString(), a.hasPin());
        });
    }

    /* --------------------- PIN (chat-experience-design.md §5.4) ------------ */

    @PostMapping("/pin")
    public Mono<ChatAccountDtos.PinResponse> setPin(@RequestBody ChatAccountDtos.PinSetRequest body,
                                                    ServerWebExchange exchange) {
        return async(() -> {
            Holder h = require(exchange);
            if (body == null || !ChatPinService.validFormat(body.pin())) {
                throw new ChatApiException(HttpStatus.BAD_REQUEST, "bad_request",
                        "pin must be 4-6 digits", 0);
            }
            requirePinOk(pins.setPin(h.account().getId(), body.currentPin(), body.pin()));
            // The account is now PIN-protected: re-mint on the short TTL so this
            // device starts the unlock cadence immediately.
            String pass = devicePasses.issueFor(new VerifiedDevice(
                    h.account().getPhoneE164(), h.account().getName(), h.device().deviceId(),
                    h.account().getId().toString(), (long) h.account().getPassGen()), true);
            return new ChatAccountDtos.PinResponse(pass, true);
        });
    }

    @DeleteMapping("/pin")
    public Mono<ChatAccountDtos.PinResponse> removePin(@RequestBody ChatAccountDtos.PinRemoveRequest body,
                                                       ServerWebExchange exchange) {
        return async(() -> {
            Holder h = require(exchange);
            requirePinOk(pins.removePin(h.account().getId(),
                    body == null ? null : body.currentPin()));
            String pass = devicePasses.issueFor(new VerifiedDevice(
                    h.account().getPhoneE164(), h.account().getName(), h.device().deviceId(),
                    h.account().getId().toString(), (long) h.account().getPassGen()), false);
            return new ChatAccountDtos.PinResponse(pass, false);
        });
    }

    /* ------------------- devices (chat-experience-design.md §5.4) ---------- */

    @GetMapping("/devices")
    public Mono<ChatAccountDtos.DevicesResponse> listDevices(ServerWebExchange exchange) {
        return async(() -> {
            Holder h = require(exchange);
            List<ChatAccountDtos.DeviceView> views = devices.list(h.account().getId()).stream()
                    .filter(d -> !d.isRevoked())
                    .map(d -> new ChatAccountDtos.DeviceView(
                            d.getDeviceId(), d.getLabel(), d.isPinRequired(),
                            d.getDeviceId().equals(h.device().deviceId()),
                            d.getCreatedAt() == null ? null : d.getCreatedAt().toString(),
                            d.getLastSeenAt() == null ? null : d.getLastSeenAt().toString()))
                    .toList();
            return new ChatAccountDtos.DevicesResponse(views);
        });
    }

    /** Lazy migration for apps holding a pre-registry pass: prove the pass, get
     *  this device's secret — no SMS spent. */
    @PostMapping("/devices/enroll")
    public Mono<ChatAccountDtos.DeviceEnrollResponse> enrollDevice(ServerWebExchange exchange) {
        return async(() -> {
            Holder h = require(exchange);
            ChatDeviceService.Enrollment e = devices.enroll(
                    h.account().getId(), h.device().deviceId(), userAgent(exchange));
            return new ChatAccountDtos.DeviceEnrollResponse(
                    e.deviceSecret(), e.pinRequired(), h.account().hasPin());
        });
    }

    @PutMapping("/devices/{deviceId}")
    public Mono<Void> updateDevice(@PathVariable("deviceId") String deviceId,
                                   @RequestBody ChatAccountDtos.DeviceUpdateRequest body,
                                   ServerWebExchange exchange) {
        return asyncVoid(() -> {
            Holder h = require(exchange);
            if (body == null || body.pinRequired() == null) {
                throw new ChatApiException(HttpStatus.BAD_REQUEST, "bad_request",
                        "pinRequired is required", 0);
            }
            // Turning the PIN gate OFF is a security downgrade: step-up with the
            // current PIN when the account has one. Turning it ON is always free.
            if (!body.pinRequired() && h.account().hasPin()) {
                requirePinOk(pins.verify(h.account().getId(), body.currentPin()));
            }
            if (!devices.setPinRequired(h.account().getId(), deviceId, body.pinRequired())) {
                throw new ChatApiException(HttpStatus.NOT_FOUND, "not_found", "no such device", 0);
            }
        });
    }

    /** Single-device sign-out; revoking the current device is plain "log out". */
    @PostMapping("/devices/{deviceId}/revoke")
    public Mono<Void> revokeDevice(@PathVariable("deviceId") String deviceId,
                                   ServerWebExchange exchange) {
        return asyncVoid(() -> {
            Holder h = require(exchange);
            if (!devices.revoke(h.account().getId(), deviceId)) {
                throw new ChatApiException(HttpStatus.NOT_FOUND, "not_found", "no such device", 0);
            }
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
            requireWithinLimit(rateLimiter.hit("verify-global", "all", limits.getGlobalDailyStarts(), DAY));
            requireWithinLimit(rateLimiter.hit("verify-start-ip", clientIp(exchange), limits.getStartPerIpPerHour(), HOUR));
            requireWithinLimit(rateLimiter.hit("verify-start-phone", a.getPhoneE164(), limits.getStartPerPhonePerDay(), DAY));
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
            requireWithinLimit(rateLimiter.hit("verify-check-phone", a.getPhoneE164(), limits.getCheckPerPhonePerHour(), HOUR));
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
            // Media the account uploaded and its receipt wallet are erased with
            // the conversations (chat-experience-design.md §6.3, §7.5).
            chatAttachments.deleteForAccount(a.getId());
            chatReceipts.deleteForAccount(a.getId());
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

    /* --------------------- receipts wallet (chat-experience §7.5) ---------- */

    @GetMapping("/receipts")
    public Mono<ChatAccountDtos.ReceiptsResponse> receipts(
            @RequestParam(value = "cursor", required = false) String cursor,
            ServerWebExchange exchange) {
        return async(() -> {
            ChatAccountEntity a = require(exchange).account();
            ChatReceiptService.WalletPage page = chatReceipts.wallet(a.getId(), cursor);
            List<ChatAccountDtos.ReceiptView> views = page.receipts().stream()
                    .map(r -> new ChatAccountDtos.ReceiptView(
                            r.getId(), r.getHandle(), r.getTemplateId(),
                            r.getTemplateSnapshot(), r.getData(),
                            r.getCreatedAt() == null ? null : r.getCreatedAt().toString()))
                    .toList();
            return new ChatAccountDtos.ReceiptsResponse(views, page.nextCursor());
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
        return devicePasses.issueFor(new VerifiedDevice(
                        acct.phoneE164(), acct.name(), device.deviceId(), acct.id(), acct.passGen()),
                accounts.hasPin(UUID.fromString(acct.id())));
    }

    private static void requirePinOk(ChatPinService.PinResult result) {
        if (result instanceof ChatPinService.Denied denied) {
            throw new ChatApiException(HttpStatus.UNAUTHORIZED,
                    denied.otpRequired() ? "otp_required" : "pin_denied",
                    "PIN check failed", 0);
        }
    }

    private static String userAgent(ServerWebExchange exchange) {
        String ua = exchange.getRequest().getHeaders().getFirst("User-Agent");
        return ua == null || ua.isBlank() ? null : ua;
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
