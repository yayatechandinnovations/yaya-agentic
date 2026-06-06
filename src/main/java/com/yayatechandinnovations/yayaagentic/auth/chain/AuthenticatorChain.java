package com.yayatechandinnovations.yayaagentic.auth.chain;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.auth.AuthenticationException;
import com.yayatechandinnovations.yayaagentic.auth.dev.NoopAuthenticator;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Single Authenticator the engine sees. Composes every ordered candidate
 * (except self + the anonymous fallback) and returns the first that
 * recognises the inbound request. Falls back to {@link NoopAuthenticator}
 * only when {@code yaya.agentic.auth.allow-anonymous} is true. See design
 * §5.4.
 */
@Component
@Primary
public class AuthenticatorChain implements Authenticator {

    private final List<Authenticator> candidates;
    private final NoopAuthenticator anonymousFallback;
    private final YayaAgenticProperties props;

    public AuthenticatorChain(List<Authenticator> all,
                              NoopAuthenticator anonymousFallback,
                              YayaAgenticProperties props) {
        this.candidates = all.stream()
                .filter(a -> a != null && a != anonymousFallback)
                .filter(a -> !(a instanceof AuthenticatorChain))
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .toList();
        this.anonymousFallback = anonymousFallback;
        this.props = props;
    }

    @Override
    public String name() { return "chain"; }

    @Override
    public Optional<Principal> tryAuthenticate(AuthContext ctx) throws AuthenticationException {
        for (Authenticator a : candidates) {
            Optional<Principal> maybe = a.tryAuthenticate(ctx);
            if (maybe.isPresent()) return maybe;
        }
        if (props.auth() != null && props.auth().allowAnonymous()) {
            return anonymousFallback.tryAuthenticate(ctx);
        }
        return Optional.empty();
    }

    @Override
    public Principal authenticate(AuthContext ctx) throws AuthenticationException {
        return tryAuthenticate(ctx).orElseThrow(() ->
                new AuthenticationException("no authenticator recognised the request and anonymous is disabled"));
    }
}
