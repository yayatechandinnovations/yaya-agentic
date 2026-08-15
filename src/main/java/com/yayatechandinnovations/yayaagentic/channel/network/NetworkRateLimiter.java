package com.yayatechandinnovations.yayaagentic.channel.network;

import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties.Network.RateLimits;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * App-level rate limits for the network entry point, in front of any provider
 * limits (yaya-agentic-network-chat-runtime-design.md §6). Reuses the Redis
 * fixed-window {@link ChatRateLimiter}. Ceilings are env-tunable via
 * {@code yaya.agentic.network.rate-limits.*}.
 */
@Component
public class NetworkRateLimiter {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final Duration DAY = Duration.ofDays(1);

    private final ChatRateLimiter limiter;
    private final YayaAgenticProperties props;

    public NetworkRateLimiter(ChatRateLimiter limiter, YayaAgenticProperties props) {
        this.limiter = limiter;
        this.props = props;
    }

    private RateLimits limits() {
        return props.network().rateLimits();
    }

    /** Per-phone broker calls (across sessions). */
    public boolean allowBrokerCall(String phone) {
        return limiter.hit("net-broker-phone", safe(phone),
                limits().brokerCallsPerPhonePerHour(), HOUR).allowed();
    }

    /** Per-session picker emissions. */
    public boolean allowPicker(String sessionId) {
        return limiter.hit("net-picker-sess", safe(sessionId),
                limits().pickerPerSessionPerHour(), HOUR).allowed();
    }

    /** Per-session link confirms. */
    public boolean allowLinkConfirm(String sessionId) {
        return limiter.hit("net-link-sess", safe(sessionId),
                limits().linkConfirmsPerSessionPerDay(), DAY).allowed();
    }

    /** Per-session play_confirm attempts. */
    public boolean allowPlayConfirm(String sessionId) {
        return limiter.hit("net-playconfirm-sess", safe(sessionId),
                limits().playConfirmsPerSessionPerHour(), HOUR).allowed();
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s;
    }
}
