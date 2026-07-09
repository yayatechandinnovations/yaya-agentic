package com.yayatechandinnovations.yayasales.platform.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Resolves {@code X-Yaya-Service-Token} into a {@link Caller.Service}
 * authentication. An invalid token is rejected outright (401) — a caller that
 * presents a service token is claiming to be the platform, and a bad claim must
 * never fall through to anonymous.
 *
 * <p>The verified caller is also stashed as request attribute
 * {@link #SERVICE_CALLER} so agent-API endpoints can require the service proof
 * even when a shopper JWT also authenticated the request (§11.3: SERVICE always,
 * PRINCIPAL when the shopper is signed in).
 */
@Component
public class ServiceTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Yaya-Service-Token";
    public static final String SERVICE_CALLER = "yaya.sales.serviceCaller";

    private final ServiceTokenVerifier verifier;

    public ServiceTokenFilter(ServiceTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        Optional<Caller.Service> service = verifier.verify(token);
        if (service.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid service token");
            return;
        }

        request.setAttribute(SERVICE_CALLER, service.get());
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            SecurityContextHolder.getContext().setAuthentication(new ServiceAuthentication(service.get()));
        }
        chain.doFilter(request, response);
    }

    static final class ServiceAuthentication extends AbstractAuthenticationToken {

        private final Caller.Service caller;

        ServiceAuthentication(Caller.Service caller) {
            super(List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
            this.caller = caller;
            setAuthenticated(true);
        }

        @Override public Object getCredentials() { return ""; }
        @Override public Object getPrincipal() { return caller; }
    }
}
