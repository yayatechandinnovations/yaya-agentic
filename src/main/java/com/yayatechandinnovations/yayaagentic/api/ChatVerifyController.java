package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.PublicChatDtos;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.DevicePassService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.PhoneVerifier;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.VerifiedDevice;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
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

    // §20.4 ceilings — deliberately tight; these gate real money (Twilio + LLM).
    private static final int START_PER_IP_PER_HOUR = 5;
    private static final int START_PER_PHONE_PER_DAY = 5;
    private static final int START_GLOBAL_PER_DAY = 5000;
    private static final int CHECK_PER_PHONE_PER_HOUR = 10;

    private final PhoneVerifier verifier;
    private final DevicePassService devicePasses;
    private final ChatRateLimiter rateLimiter;

    public ChatVerifyController(PhoneVerifier verifier,
                                DevicePassService devicePasses,
                                ChatRateLimiter rateLimiter) {
        this.verifier = verifier;
        this.devicePasses = devicePasses;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/start")
    public Mono<PublicChatDtos.VerifyStartResponse> start(
            @RequestBody PublicChatDtos.VerifyStartRequest body,
            ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            String phone = requireE164(body == null ? null : body.phoneE164());

            // Order matters: cheapest/global guard first, then per-identity.
            requireWithinLimit(rateLimiter.hit("verify-global", "all", START_GLOBAL_PER_DAY, DAY));
            requireWithinLimit(rateLimiter.hit("verify-start-ip", clientIp(exchange), START_PER_IP_PER_HOUR, HOUR));
            requireWithinLimit(rateLimiter.hit("verify-start-phone", phone, START_PER_PHONE_PER_DAY, DAY));

            PhoneVerifier.StartResult result = verifier.start(phone);
            if (!result.ok()) {
                throw new ChatApiException(HttpStatus.BAD_GATEWAY, "verify_failed",
                        "could not send a verification code", 0);
            }
            return new PublicChatDtos.VerifyStartResponse("pending");
        });
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
            requireWithinLimit(rateLimiter.hit("verify-check-phone", phone, CHECK_PER_PHONE_PER_HOUR, HOUR));

            PhoneVerifier.CheckResult result = verifier.check(phone, body.code().trim());
            if (!result.approved()) {
                throw new ChatApiException(HttpStatus.UNAUTHORIZED, "verify_denied",
                        "that code is invalid or expired", 0);
            }
            String name = body.name() == null ? null : body.name().trim();
            String pass = devicePasses.issue(new VerifiedDevice(phone, name, safe(body.deviceId())));
            return new PublicChatDtos.VerifyCheckResponse(true, pass, phone, name);
        });
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

    private static String clientIp(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return exchange.getRequest().getRemoteAddress() == null
                ? "unknown"
                : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }
}
