package com.yayatechandinnovations.yayaidentity.credentials;

import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import org.springframework.stereotype.Component;

@Component
public class PasswordCredentialProvider implements CredentialProvider {

    private final CredentialService credentials;

    public PasswordCredentialProvider(CredentialService credentials) {
        this.credentials = credentials;
    }

    @Override
    public CredentialKind kind() {
        return CredentialKind.PASSWORD;
    }

    @Override
    public boolean verify(UserId user, String input) {
        return credentials.verifyPassword(user, input);
    }
}
