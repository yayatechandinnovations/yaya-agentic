package com.yayatechandinnovations.yayaidentity.signup;

import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;

import java.util.Optional;

/**
 * Where signup OTPs are generated, delivered, and checked. Two shapes exist:
 * Twilio Verify holds the code on Twilio's side (start/check are remote calls,
 * nothing stored locally); the local channel stores a hashed code in credentials
 * and leaves delivery to the operator. {@code start} returns the raw code ONLY
 * when it was generated locally — the service echoes it solely under the
 * dev-only {@code otp-echo-enabled} flag.
 */
public interface OtpChannel {

    Optional<String> start(UserId user, String phoneE164);

    boolean check(UserId user, String phoneE164, String code);
}
