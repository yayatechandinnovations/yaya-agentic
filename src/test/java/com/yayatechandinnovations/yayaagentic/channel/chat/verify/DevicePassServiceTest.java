package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The device pass is the §20 trust anchor for verified-phone chat — it must
 * round-trip cleanly and reject anything it didn't sign.
 */
class DevicePassServiceTest {

    private final DevicePassService svc = new DevicePassService("test-secret-key", 30, 24);

    @Test
    void issue_then_verify_round_trips_the_device() {
        String pass = svc.issue(new VerifiedDevice("+18095551234", "Ada", "dev-1"));
        Optional<VerifiedDevice> back = svc.verify(pass);
        assertThat(back).isPresent();
        assertThat(back.get().phoneE164()).isEqualTo("+18095551234");
        assertThat(back.get().name()).isEqualTo("Ada");
        assertThat(back.get().deviceId()).isEqualTo("dev-1");
    }

    @Test
    void a_pass_signed_by_a_different_key_is_rejected() {
        String pass = new DevicePassService("attacker-key", 30, 24)
                .issue(new VerifiedDevice("+18095559999", "Mallory", "dev-x"));
        assertThat(svc.verify(pass)).isEmpty();
    }

    @Test
    void pin_protected_pass_gets_the_short_ttl() throws Exception {
        String longPass = svc.issueFor(new VerifiedDevice("+18095551234", "Ada", "d"), false);
        String shortPass = svc.issueFor(new VerifiedDevice("+18095551234", "Ada", "d"), true);
        var longExp = com.nimbusds.jwt.SignedJWT.parse(longPass).getJWTClaimsSet().getExpirationTime();
        var shortExp = com.nimbusds.jwt.SignedJWT.parse(shortPass).getJWTClaimsSet().getExpirationTime();
        // 24h vs 30d — the short pass must expire well before the long one.
        assertThat(shortExp).isBefore(longExp);
        assertThat(shortExp.toInstant())
                .isBefore(java.time.Instant.now().plus(java.time.Duration.ofHours(25)));
    }

    @Test
    void garbage_and_null_are_rejected() {
        assertThat(svc.verify(null)).isEmpty();
        assertThat(svc.verify("")).isEmpty();
        assertThat(svc.verify("not.a.jwt")).isEmpty();
    }
}
