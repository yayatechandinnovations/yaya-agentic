package com.yayatechandinnovations.yayaidentity.credentials;

import com.yayatechandinnovations.yayaidentity.users.UserEntity;
import com.yayatechandinnovations.yayaidentity.users.UserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * Binds a user + password credential to Spring Security for the auth-code human
 * login. The submitted username is the email; the resulting principal name is
 * the stable user id (so token {@code sub} is the id, not the email). Authorities
 * are empty in I1 — scoped roles arrive in the token at I2.
 */
@Service
public class IdentityUserDetailsService implements UserDetailsService {

    private final UserRepository users;
    private final CredentialService credentials;
    private final LoginAttemptService loginAttempts;

    public IdentityUserDetailsService(UserRepository users, CredentialService credentials,
                                      LoginAttemptService loginAttempts) {
        this.users = users;
        this.credentials = credentials;
        this.loginAttempts = loginAttempts;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        if (loginAttempts.isLocked(email)) {
            throw new org.springframework.security.authentication.LockedException(
                    "account temporarily locked after too many failed attempts");
        }
        UserEntity user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("no such user"));
        CredentialEntity password = credentials.activePassword(user.getId())
                .orElseThrow(() -> new UsernameNotFoundException("no password credential"));

        String status = user.getStatus();
        return User.withUsername(user.getId())
                .password(password.getSecretRef())          // Argon2id {argon2}… hash
                .authorities(Collections.emptyList())
                .disabled(!"ACTIVE".equals(status))
                .accountLocked("LOCKED".equals(status))
                .build();
    }
}
