package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dev / test phone verifier — the fallback {@link PhoneVerifierConfig} registers
 * (via {@code @ConditionalOnMissingBean}) until {@link TwilioVerifyPhoneVerifier}
 * takes over when a Twilio Verify service SID is configured. Never sends a real
 * SMS; accepts the fixed code {@code 123456} so the onboarding flow is
 * exercisable offline.
 */
public class DevNoopPhoneVerifier implements PhoneVerifier {

    private static final Logger log = LoggerFactory.getLogger(DevNoopPhoneVerifier.class);
    static final String DEV_CODE = "123456";

    @Override
    public String name() { return "dev-noop-verify"; }

    @Override
    public StartResult start(String phoneE164) {
        log.info("[dev] phone verification requested for {} — use code {} (no SMS sent)", phoneE164, DEV_CODE);
        return StartResult.pending();
    }

    @Override
    public CheckResult check(String phoneE164, String code) {
        return DEV_CODE.equals(code) ? CheckResult.pass() : CheckResult.denied("invalid code");
    }
}
