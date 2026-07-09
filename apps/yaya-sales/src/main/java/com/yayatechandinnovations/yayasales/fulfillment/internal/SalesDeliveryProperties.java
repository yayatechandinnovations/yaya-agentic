package com.yayatechandinnovations.yayasales.fulfillment.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fleet knobs. {@code otpEchoEnabled} returns the registration OTP in the
 * register response for dev/test ONLY (default off) — production wires an SMS
 * sender to the {@code delivery_agent.registered} outbox event instead; the
 * OTP is always stored hashed either way.
 */
@ConfigurationProperties(prefix = "yaya.sales.delivery")
public record SalesDeliveryProperties(
        Boolean otpEchoEnabled,
        Integer otpTtlMinutes,
        Integer inviteTtlHours) {

    public boolean otpEcho() {
        return Boolean.TRUE.equals(otpEchoEnabled);
    }

    public int otpTtl() {
        return otpTtlMinutes == null ? 10 : otpTtlMinutes;
    }

    public int inviteTtl() {
        return inviteTtlHours == null ? 72 : inviteTtlHours;
    }
}
