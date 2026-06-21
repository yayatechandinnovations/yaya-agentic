package com.yayatechandinnovations.yayaagentic.operator_auth.userpassword;

import com.yayatechandinnovations.yayaagentic.operator_auth.Operator;
import com.yayatechandinnovations.yayaagentic.operator_auth.OperatorAuthenticationException;
import com.yayatechandinnovations.yayaagentic.operator_auth.OperatorAuthenticator;
import com.yayatechandinnovations.yayaagentic.operator_auth.OperatorCredentials;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.UserStatus;
import com.yayatechandinnovations.yayaagentic.persistence.UserEntity;
import com.yayatechandinnovations.yayaagentic.persistence.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.CharBuffer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Recognises {@code OperatorCredentials} whose {@code username} resolves
 * to a registered user. Ordered before delegate / bootstrap.
 *
 * <p>Yield-vs-throw contract:
 * <ul>
 *   <li>{@code Optional.empty()} — {@code username} doesn't match any
 *       user row OR user-accounts are disabled by config. Lets the chain
 *       try the next strategy.</li>
 *   <li>throw — user exists but password mismatch, or status forbids login
 *       (pending / suspended / deleted). Stops the chain with 401.</li>
 *   <li>present — verified user; the controller mints a session with
 *       {@code source=USER_PASSWORD} and {@code subject=user.id}.</li>
 * </ul>
 * </p>
 */
@Component
@Order(100)
public class UserPasswordOperatorAuthenticator implements OperatorAuthenticator {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final boolean enabled;

    @Autowired
    public UserPasswordOperatorAuthenticator(
            UserRepository users,
            PasswordEncoder encoder,
            @Value("${yaya.agentic.user-accounts.enabled:false}") boolean enabled) {
        this.users = users;
        this.encoder = encoder;
        this.enabled = enabled;
    }

    @Override
    public String name() { return "user-password"; }

    @Override
    @Transactional
    public Optional<Operator> tryAuthenticate(OperatorCredentials creds)
            throws OperatorAuthenticationException {
        if (!enabled) return Optional.empty();
        if (creds == null || isBlank(creds.username()) || creds.password() == null) {
            return Optional.empty();
        }

        Optional<UserEntity> maybe = users.findActiveByEmail(creds.username());
        if (maybe.isEmpty()) {
            // Yield: not a user-password attempt; let bootstrap / delegate try.
            return Optional.empty();
        }
        UserEntity user = maybe.get();

        // From here on, this strategy CLAIMS the attempt. Any failure throws
        // so the chain stops (no fallback to bootstrap with the same email).
        if (UserStatus.PENDING_VERIFICATION.name().equals(user.getStatus())) {
            throw new OperatorAuthenticationException("user-password: account_unverified");
        }
        if (UserStatus.SUSPENDED.name().equals(user.getStatus())) {
            throw new OperatorAuthenticationException("user-password: account_suspended");
        }
        if (UserStatus.DELETED.name().equals(user.getStatus())) {
            throw new OperatorAuthenticationException("user-password: account_deleted");
        }

        CharSequence presented = CharBuffer.wrap(creds.password());
        if (user.getPasswordHash() == null || user.getPasswordHash().isEmpty()
                || !encoder.matches(presented, user.getPasswordHash())) {
            throw new OperatorAuthenticationException("user-password: password mismatch");
        }

        user.setLastLoginAt(OffsetDateTime.now(ZoneOffset.UTC));

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("user_id", user.getId().toString());
        attrs.put("email", user.getEmail());
        attrs.put("is_platform_admin", user.isPlatformAdmin());

        return Optional.of(new Operator(
                user.getId().toString(),
                user.getDisplayName(),
                Operator.Source.USER_PASSWORD,
                attrs,
                Instant.now()));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
