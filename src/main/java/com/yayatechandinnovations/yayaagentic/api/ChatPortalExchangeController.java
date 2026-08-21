package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.PublicChatDtos;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointService;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.ChatAccountService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.ChatDeviceService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.ChatVerifyLimitsProperties;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.DevicePassService;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.PortalIntrospectionClient;
import com.yayatechandinnovations.yayaagentic.channel.chat.verify.VerifiedDevice;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.UUID;

/**
 * Portal-session exchange ({@code corillo-chat-portal-session-exchange-design.md}):
 * mints a chat device pass from a live hub portal session instead of a second
 * SMS. The tenant's hub already phone-OTP-verified the caller at portal login;
 * this endpoint introspects the presented portal JWT against that hub
 * ({@code GET /api/portal/me}, resolved per tenant like the broker plane) and,
 * on success, runs exactly the post-OTP mint tail of {@code /verify/check}:
 * complete the registration, enroll the device, issue the pass.
 *
 * <p>Entry-point-scoped (unlike {@code /v1/chat/verify/*}) because the tenant
 * behind {@code handle} is what decides which hub is trusted to attest phones.
 *
 * <p>Every failure mode — bad/expired token, unreachable hub, unconfigured hub
 * base URL — collapses into one uniform 401 so nothing can be enumerated; the
 * app falls back to the normal OTP flow.
 */
@RestController
@RequestMapping("/v1/chat")
public class ChatPortalExchangeController {

    private static final Duration HOUR = Duration.ofHours(1);

    private final ChatEntryPointService entryPoints;
    private final PortalIntrospectionClient portalIntrospection;
    private final ChatAccountService accounts;
    private final ChatDeviceService devices;
    private final DevicePassService devicePasses;
    private final ChatRateLimiter rateLimiter;
    private final ChatVerifyLimitsProperties limits;

    public ChatPortalExchangeController(ChatEntryPointService entryPoints,
                                        PortalIntrospectionClient portalIntrospection,
                                        ChatAccountService accounts,
                                        ChatDeviceService devices,
                                        DevicePassService devicePasses,
                                        ChatRateLimiter rateLimiter,
                                        ChatVerifyLimitsProperties limits) {
        this.entryPoints = entryPoints;
        this.portalIntrospection = portalIntrospection;
        this.accounts = accounts;
        this.devices = devices;
        this.devicePasses = devicePasses;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    @PostMapping("/e/{handle}/verify/portal-exchange")
    public Mono<PublicChatDtos.VerifyCheckResponse> exchange(
            @PathVariable("handle") String handle,
            @RequestBody PublicChatDtos.PortalExchangeRequest body,
            ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> {
            if (body == null || body.portalToken() == null || body.portalToken().isBlank()) {
                throw exchangeDenied();
            }
            ChatEntryPoint ep = entryPoints.findByHandle(handle)
                    .orElseThrow(ChatApiException::unknownHandle);
            if (!ep.status().servesSessions()) throw ChatApiException.unknownHandle();

            String deviceId = safe(body.deviceId());
            requireWithinLimit(rateLimiter.hit("portal-exchange-ip", clientIp(exchange),
                    limits.getExchangePerIpPerHour(), HOUR));
            requireWithinLimit(rateLimiter.hit("portal-exchange-dev", deviceId,
                    limits.getExchangePerDevicePerHour(), HOUR));

            // The hub is the authority on the phone: the caller never supplies it,
            // so a stolen portal token can only mint a pass for the phone the hub
            // already verified for that session.
            PortalIntrospectionClient.PortalIdentity identity = portalIntrospection
                    .introspect(ep.tenant(), body.portalToken())
                    .orElseThrow(ChatPortalExchangeController::exchangeDenied);

            // Same tail as /verify/check after an approved OTP: a name sent by the
            // app wins over the portal-derived one; returning accounts keep their
            // stored name either way (completeVerification semantics).
            String name = body.name() == null || body.name().isBlank()
                    ? identity.name() : body.name();
            ChatAccountService.VerifiedAccount acct =
                    accounts.completeVerification(identity.phoneE164(), name);
            UUID accountId = UUID.fromString(acct.id());
            ChatDeviceService.Enrollment enrollment =
                    devices.enroll(accountId, deviceId, userAgent(exchange));
            boolean pinSet = accounts.hasPin(accountId);
            String pass = devicePasses.issueFor(new VerifiedDevice(
                    identity.phoneE164(), acct.name(), deviceId, acct.id(), acct.passGen()), pinSet);
            return new PublicChatDtos.VerifyCheckResponse(true, pass, identity.phoneE164(),
                    acct.name(), enrollment.deviceSecret(), pinSet);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /* ------------------------------ helpers ------------------------------- */

    private void requireWithinLimit(ChatRateLimiter.Decision d) {
        if (!d.allowed()) throw ChatApiException.rateLimited(d.retryAfterSeconds());
    }

    private static ChatApiException exchangeDenied() {
        return new ChatApiException(HttpStatus.UNAUTHORIZED, "portal_exchange_denied",
                "could not verify the portal session", 0);
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "nodev" : s.trim();
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
