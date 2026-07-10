package com.yayatechandinnovations.yayaidentity.signup;

import com.yayatechandinnovations.yayaidentity.credentials.CredentialService;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Self-hosted fallback: the hashed, TTL'd, attempt-limited OTP machinery in
 * {@link CredentialService}. Delivery is the operator's problem (or the dev-only
 * echo flag) — the code is deliberately NOT logged.
 */
public class LocalOtpChannel implements OtpChannel {

    private static final Logger log = LoggerFactory.getLogger(LocalOtpChannel.class);

    private final CredentialService credentials;

    public LocalOtpChannel(CredentialService credentials) {
        this.credentials = credentials;
    }

    @Override
    public Optional<String> start(UserId user, String phoneE164) {
        String code = credentials.issueOtp(user);
        log.info("Signup OTP issued for {} — no SMS gateway configured; deliver out-of-band "
                + "or enable yaya.identity.signup.otp-echo-enabled in dev.", phoneE164);
        return Optional.of(code);
    }

    @Override
    public boolean check(UserId user, String phoneE164, String code) {
        return credentials.verifyOtp(user, code);
    }
}
