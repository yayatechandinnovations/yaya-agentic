package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verify/PIN-unlock ceilings are env-tunable
 * ({@code yaya.agentic.chat.verify.limits.*}) so operators can loosen them for
 * testing without a code change; unset keys keep the tight production defaults.
 */
class ChatVerifyLimitsPropertiesTest {

    @Test
    void defaultsAreTheShippedProductionCeilings() {
        ChatVerifyLimitsProperties p = new ChatVerifyLimitsProperties();
        assertThat(p.getStartPerPhonePerDay()).isEqualTo(5);
        assertThat(p.getStartPerIpPerHour()).isEqualTo(5);
        assertThat(p.getGlobalDailyStarts()).isEqualTo(5000);
        assertThat(p.getCheckPerPhonePerHour()).isEqualTo(10);
        assertThat(p.getLookupPerIpPerHour()).isEqualTo(30);
        assertThat(p.getUnlockPerIpPerHour()).isEqualTo(60);
        assertThat(p.getUnlockPerDevicePerHour()).isEqualTo(20);
    }

    @Test
    void envOverridesRaiseTheCeiling() {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "yaya.agentic.chat.verify.limits.start-per-phone-per-day", "500",
                "yaya.agentic.chat.verify.limits.check-per-phone-per-hour", "200"));
        ChatVerifyLimitsProperties p = new Binder(source)
                .bind("yaya.agentic.chat.verify.limits", ChatVerifyLimitsProperties.class)
                .get();
        assertThat(p.getStartPerPhonePerDay()).isEqualTo(500);
        assertThat(p.getCheckPerPhonePerHour()).isEqualTo(200);
        // Unspecified keys keep their defaults.
        assertThat(p.getGlobalDailyStarts()).isEqualTo(5000);
    }
}
