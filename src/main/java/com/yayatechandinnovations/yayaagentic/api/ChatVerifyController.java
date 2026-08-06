package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.PublicChatDtos;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.ChatAccountService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.ChatDeviceService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.ChatPinService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.ChatVerifyLimitsProperties;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.DevicePassService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.PhoneVerifier;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.VerifiedDevice;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * First-run phone verification for the chat app ({@code chat-channel-design.md} §20).
 * A successful Twilio Verify mints a device pass — a platform-signed token the
 * app then presents on every chat request to be identified as a verified human.
 *
 * <p>This is the highest-risk public surface (toll fraud / SMS pumping, §20.4),
 * so {@code verify/start} is the most aggressively rate-limited endpoint in the
 * app: per IP, per phone, and a hard global daily ceiling — all before a single
 * SMS is sent.
 */
@RestController
@RequestMapping("/v1/chat/verify")
public class ChatVerifyController {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{6,17}$");
    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DAY = Duration.ofHours(24);

    // §20.4 ceilings — env-tunable via yaya.agentic.chat.verify.limits.*;
    // defaults stay tight because start gates real money (Twilio + LLM).
    private final ChatVerifyLimitsProperties limits;
    private final PhoneVerifier verifier;
    private final DevicePassService devicePasses;
    private final ChatRateLimiter rateLimiter;
    private final ChatAccountService accounts;
    private final ChatDeviceService devices;
    private final ChatPinService pins;

    public ChatVerifyController(PhoneVerifier verifier,
                                DevicePassService devicePasses,
                                ChatRateLimiter rateLimiter,
                                ChatAccountService accounts,
                                ChatDeviceService devices,
                                ChatPinService pins,
                                ChatVerifyLimitsProperties limits) {
        this.verifier = verifier;
        this.devicePasses = devicePasses;
        this.rateLimiter = rateLimiter;
        this.accounts = accounts;
        this.devices = devices;
        this.pins = pins;
        this.limits = limits;
    }

    /** Phone-first probe. Reveals only whether a completed registration exists so
     *  the app knows whether to collect a name — no SMS, no device pass. */
    @PostMapping("/lookup")
    public Mono<PublicChatDtos.VerifyLookupResponse> lookup(
            @RequestBody PublicChatDtos.VerifyLookupRequest body,
            ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            String phone = requireE164(body == null ? null : body.phoneE164());
            requireWithinLimit(rateLimiter.hit("verify-lookup-ip", clientIp(exchange), limits.getLookupPerIpPerHour(), HOUR));
            return new PublicChatDtos.VerifyLookupResponse(accounts.isRegistered(phone));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/start")
    public Mono<PublicChatDtos.VerifyStartResponse> start(
            @RequestBody PublicChatDtos.VerifyStartRequest body,
            ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            String phone = requireE164(body == null ? null : body.phoneE164());

            // Order matters: cheapest/global guard first, then per-identity.
            requireWithinLimit(rateLimiter.hit("verify-global", "all", limits.getGlobalDailyStarts(), DAY));
            requireWithinLimit(rateLimiter.hit("verify-start-ip", clientIp(exchange), limits.getStartPerIpPerHour(), HOUR));
            requireWithinLimit(rateLimiter.hit("verify-start-phone", phone, limits.getStartPerPhonePerDay(), DAY));

            PhoneVerifier.StartResult result = verifier.start(phone);
            if (!result.ok()) {
                throw new ChatApiException(HttpStatus.BAD_GATEWAY, "verify_failed",
                        "could not send a verification code", 0);
            }
            return new PublicChatDtos.VerifyStartResponse("pending");
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/check")
    public Mono<PublicChatDtos.VerifyCheckResponse> check(
            @RequestBody PublicChatDtos.VerifyCheckRequest body,
            ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            if (body == null) throw badRequest("missing body");
            String phone = requireE164(body.phoneE164());
            if (body.code() == null || body.code().isBlank()) throw badRequest("code required");

            // Brute-force guard on the code.
            requireWithinLimit(rateLimiter.hit("verify-check-phone", phone, limits.getCheckPerPhonePerHour(), HOUR));

            PhoneVerifier.CheckResult result = verifier.check(phone, body.code().trim());
            if (!result.approved()) {
                throw new ChatApiException(HttpStatus.UNAUTHORIZED, "verify_denied",
                        "that code is invalid or expired", 0);
            }
            // Persist the registration (first time) or re-authenticate a returning
            // phone; the returned name is the account's — a returning device that
            // sent no name still gets its stored one back on the pass. The pass
            // carries the account id + generation so it can be revoked (§5.2).
            ChatAccountService.VerifiedAccount acct = accounts.completeVerification(phone, body.name());
            String deviceId = safe(body.deviceId());
            // Enroll the device (chat-experience-design.md §5.4): the secret goes
            // back exactly once; from here on this device unlocks with the PIN
            // instead of another SMS.
            UUID accountId = UUID.fromString(acct.id());
            ChatDeviceService.Enrollment enrollment = devices.enroll(accountId, deviceId, userAgent(exchange));
            boolean pinSet = accounts.hasPin(accountId);
            String pass = devicePasses.issueFor(new VerifiedDevice(
                    phone, acct.name(), deviceId, acct.id(), acct.passGen()), pinSet);
            return new PublicChatDtos.VerifyCheckResponse(true, pass, phone, acct.name(),
                    enrollment.deviceSecret(), pinSet);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * The everyday login ({@code chat-experience-design.md} §5.4): device secret
     * proves the enrolled device, PIN proves the person, and a fresh short-lived
     * pass comes back. Unauthenticated by design — this IS the authentication.
     * Every failure mode (unknown device, wrong secret, revoked, wrong PIN,
     * locked) collapses into one uniform 401 so nothing can be enumerated.
     */
    @PostMapping("/pin-unlock")
    public Mono<PublicChatDtos.PinUnlockResponse> pinUnlock(
            @RequestBody PublicChatDtos.PinUnlockRequest body,
            ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            if (body == null) throw badRequest("missing body");
            requireWithinLimit(rateLimiter.hit("pin-unlock-ip", clientIp(exchange), limits.getUnlockPerIpPerHour(), HOUR));
            requireWithinLimit(rateLimiter.hit("pin-unlock-dev", safe(body.deviceId()), limits.getUnlockPerDevicePerHour(), HOUR));

            var device = devices.resolveBySecret(body.deviceId(), body.deviceSecret())
                    .orElseThrow(ChatVerifyController::unlockDenied);
            var account = accounts.liveById(device.getAccountId())
                    .orElseThrow(ChatVerifyController::unlockDenied);

            boolean pinRequired = account.hasPin() && device.isPinRequired();
            if (pinRequired) {
                ChatPinService.PinResult result = pins.verify(account.getId(), body.pin());
                if (result instanceof ChatPinService.Denied denied) {
                    // The one distinction the app may learn: the ladder is
                    // exhausted and only a fresh OTP resets it.
                    throw denied.otpRequired() ? otpRequired() : unlockDenied();
                }
            }
            devices.touchLastSeen(account.getId(), device.getDeviceId());
            String pass = devicePasses.issueFor(new VerifiedDevice(
                    account.getPhoneE164(), account.getName(), device.getDeviceId(),
                    account.getId().toString(), (long) account.getPassGen()), account.hasPin());
            return new PublicChatDtos.PinUnlockResponse(
                    pass, account.getPhoneE164(), account.getName(), pinRequired);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* ------------------------------ helpers ------------------------------- */

    private static String requireE164(String phone) {
        if (phone == null || !E164.matcher(phone.trim()).matches()) {
            throw badRequest("phoneE164 must be E.164 (+ then 7–18 digits)");
        }
        return phone.trim();
    }

    private void requireWithinLimit(ChatRateLimiter.Decision d) {
        if (!d.allowed()) throw ChatApiException.rateLimited(d.retryAfterSeconds());
    }

    private static ChatApiException badRequest(String message) {
        return new ChatApiException(HttpStatus.BAD_REQUEST, "bad_request", message, 0);
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "nodev" : s.trim();
    }

    private static ChatApiException unlockDenied() {
        return new ChatApiException(HttpStatus.UNAUTHORIZED, "unlock_denied",
                "could not unlock on this device", 0);
    }

    private static ChatApiException otpRequired() {
        return new ChatApiException(HttpStatus.UNAUTHORIZED, "otp_required",
                "verify your phone to continue", 0);
    }

    private static String userAgent(ServerWebExchange exchange) {
        String ua = exchange.getRequest().getHeaders().getFirst("User-Agent");
        return ua == null || ua.isBlank() ? null : ua;
    }

    private static String clientIp(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return exchange.getRequest().getRemoteAddress() == null
                ? "unknown"
                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }
}
