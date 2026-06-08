package com.yayatechandinnovations.yayaagentic.operator_auth.delegate;

import com.yayatechandinnovations.yayaagentic.operator_auth.Operator;
import com.yayatechandinnovations.yayaagentic.operator_auth.OperatorAuthenticationException;
import com.yayatechandinnovations.yayaagentic.operator_auth.OperatorAuthenticator;
import com.yayatechandinnovations.yayaagentic.operator_auth.OperatorCredentials;
import com.yayatechandinnovations.yayaagentic.operator_auth.config.OperatorAuthConfigService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Runtime adapter from the {@code OperatorAuthenticator} chain to
 * {@link DelegateInvoker}. Order 200 so it runs before the bootstrap
 * (order 1000) but after any future first-party strategies that might
 * register lower.
 *
 * <p>This class is intentionally thin — the entire success/identity
 * logic lives in the shared invoker so the admin Test button preview
 * matches the production decision exactly.</p>
 */
@Component
@Order(200)
public class HttpDelegateOperatorAuthenticator implements OperatorAuthenticator {

    private final OperatorAuthConfigService configService;
    private final DelegateInvoker invoker;

    public HttpDelegateOperatorAuthenticator(OperatorAuthConfigService configService,
                                             DelegateInvoker invoker) {
        this.configService = configService;
        this.invoker = invoker;
    }

    @Override
    public String name() { return "http-delegate"; }

    @Override
    public Optional<Operator> tryAuthenticate(OperatorCredentials creds) throws OperatorAuthenticationException {
        HttpDelegateConfig cfg = configService.delegateState();
        if (cfg == null || !cfg.enabled() || isBlank(cfg.url())) {
            return Optional.empty();
        }
        if (creds == null || isBlank(creds.username()) || creds.password() == null) {
            return Optional.empty();
        }

        DelegateInvoker.ProbeResult result = invoker.invoke(
                cfg, creds.username(), creds.password(), creds.attemptId());

        if (result.allowed()) {
            DelegateInvoker.ExtractedIdentity id = result.evaluation().identity();
            return Optional.of(new Operator(
                    id.subject(),
                    id.displayName(),
                    Operator.Source.HTTP_DELEGATE,
                    id.attributes(),
                    Instant.now()));
        }

        // The delegate is "applicable" once it's enabled with a URL; a
        // DENY short-circuits the chain rather than falling through to
        // the bootstrap. This is the secure default — a misconfigured
        // delegate shouldn't silently grant access via bootstrap unless
        // the delegate itself is unreachable.
        String reason = result.auditReason() == null ? "delegate_denied" : result.auditReason();
        if (reason.startsWith("delegate_unreachable")) {
            // Transport failure → let the chain continue so a working
            // bootstrap can rescue the operator. Design §5.8.
            return Optional.empty();
        }
        throw new OperatorAuthenticationException("http-delegate: " + reason);
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
