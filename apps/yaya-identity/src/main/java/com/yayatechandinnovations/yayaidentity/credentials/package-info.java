/**
 * Credentials module (design §8 module 3, §6.2). Pluggable {@link
 * com.yayatechandinnovations.yayaidentity.credentials.CredentialProvider}s:
 * v1 ships password (Argon2id) and phone OTP; TOTP MFA arrives at I5, WebAuthn /
 * federation vNext. Also provides the {@code UserDetailsService} that binds a
 * user + password to the auth-code human login.
 */
package com.yayatechandinnovations.yayaidentity.credentials;
