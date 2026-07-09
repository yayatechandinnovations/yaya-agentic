package com.yayatechandinnovations.yayaidentity.credentials;

import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import org.springframework.stereotype.Component;

@Component
public class PhoneOtpProvider implements CredentialProvider {

    private final CredentialService credentials;

    public PhoneOtpProvider(CredentialService credentials) {
        this.credentials = credentials;
    }

    @Override
    public CredentialKind kind() {
        return CredentialKind.PHONE_OTP;
    }

    @Override
    public boolean verify(UserId user, String input) {
        return credentials.verifyOtp(user, input);
    }
}
