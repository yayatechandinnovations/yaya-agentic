package com.yayatechandinnovations.yayaagentic.operator_auth.bootstrap;

import com.yayatechandinnovations.yayaagentic.operator_auth.Operator;
import com.yayatechandinnovations.yayaagentic.operator_auth.OperatorAuthenticationException;
import com.yayatechandinnovations.yayaagentic.operator_auth.OperatorAuthenticator;
import com.yayatechandinnovations.yayaagentic.operator_auth.OperatorCredentials;
import com.yayatechandinnovations.yayaagentic.operator_auth.config.OperatorAuthConfigService;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.CharBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Always-on break-glass operator. Reads state from
 * {@link OperatorAuthConfigService} on every call so the admin UI can
 * change the password or toggle bootstrap-enabled without a restart.
 *
 * <p>Ordered last (1000) so configured strategies take precedence; the
 * bootstrap survives upstream misconfiguration so a broken delegate URL
 * doesn't lock everyone out.</p>
 */
@Component
@Order(1000)
public class BootstrapOperatorAuthenticator implements OperatorAuthenticator {

    private final OperatorAuthConfigService configService;
    private final PasswordEncoder encoder;

    public BootstrapOperatorAuthenticator(OperatorAuthConfigService configService,
                                          PasswordEncoder encoder) {
        this.configService = configService;
        this.encoder = encoder;
    }

    @Override
    public String name() { return "bootstrap"; }

    @Override
    public Optional<Operator> tryAuthenticate(OperatorCredentials creds) throws OperatorAuthenticationException {
        OperatorAuthConfigService.BootstrapState state = configService.bootstrapState();
        if (state == null || !state.enabled()) return Optional.empty();
        if (creds == null || isBlank(creds.username())) return Optional.empty();
        if (!state.username().equals(creds.username())) return Optional.empty();

        CharSequence presented = creds.password() == null ? "" : CharBuffer.wrap(creds.password());
        if (!encoder.matches(presented, state.passwordHash())) {
            throw new OperatorAuthenticationException("bootstrap: password mismatch");
        }

        return Optional.of(new Operator(
                state.username(),
                state.username(),
                Operator.Source.BOOTSTRAP,
                Map.of(),
                Instant.now()));
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
