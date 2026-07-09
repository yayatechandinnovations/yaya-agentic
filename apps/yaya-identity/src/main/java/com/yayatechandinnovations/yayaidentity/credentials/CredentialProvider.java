package com.yayatechandinnovations.yayaidentity.credentials;

import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;

/**
 * The credential-verification SPI (design §15). Each provider verifies one
 * {@link CredentialKind}. Issuance/enrolment (setting a password, sending an
 * OTP) is orchestrated by {@link CredentialService}; providers only answer
 * "does this input satisfy the user's stored credential of my kind?".
 */
public interface CredentialProvider {

    CredentialKind kind();

    boolean verify(UserId user, String input);
}
