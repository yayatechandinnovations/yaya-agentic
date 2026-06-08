package com.yayatechandinnovations.yayaagentic.operator_auth.chain;

import com.yayatechandinnovations.yayaagentic.operator_auth.Operator;
import com.yayatechandinnovations.yayaagentic.operator_auth.OperatorAuthenticationException;
import com.yayatechandinnovations.yayaagentic.operator_auth.OperatorAuthenticator;
import com.yayatechandinnovations.yayaagentic.operator_auth.OperatorCredentials;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Composes every {@link OperatorAuthenticator} bean (except self) ordered by
 * Spring {@code @Order}. Returns the first that recognises the credentials.
 *
 * <p>Responsibility split: implementations decide allow/deny; the chain
 * guarantees {@link OperatorCredentials#clear()} runs exactly once, regardless
 * of outcome.</p>
 */
@Component
@Primary
public class OperatorAuthenticatorChain implements OperatorAuthenticator {

    private final List<OperatorAuthenticator> candidates;

    public OperatorAuthenticatorChain(List<OperatorAuthenticator> all) {
        this.candidates = all.stream()
                .filter(a -> a != null && !(a instanceof OperatorAuthenticatorChain))
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .toList();
    }

    @Override
    public String name() { return "chain"; }

    @Override
    public Optional<Operator> tryAuthenticate(OperatorCredentials creds) throws OperatorAuthenticationException {
        try {
            for (OperatorAuthenticator a : candidates) {
                try {
                    Optional<Operator> maybe = a.tryAuthenticate(creds);
                    if (maybe.isPresent()) return maybe;
                } catch (OperatorAuthenticationException ex) {
                    // Attach the source if the authenticator didn't set one — gives
                    // the audit row a definite `source` column even when impls
                    // forget to provide it.
                    if (ex.source() == null) {
                        throw new OperatorAuthenticationException(ex.getMessage(), ex, a.name());
                    }
                    throw ex;
                }
            }
            return Optional.empty();
        } finally {
            if (creds != null) creds.clear();
        }
    }
}
