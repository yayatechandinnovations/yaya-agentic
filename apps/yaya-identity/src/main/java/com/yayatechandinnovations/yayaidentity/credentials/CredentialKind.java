package com.yayatechandinnovations.yayaidentity.credentials;

/** Credential kinds (design §6.2). PASSWORD + PHONE_OTP in v1; TOTP at I5. */
public enum CredentialKind {
    PASSWORD, PHONE_OTP, TOTP
}
