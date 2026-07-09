package com.yayatechandinnovations.yayasales.platform.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;

/**
 * The fourth caller class's front door (§10.4, §15): {@code /v1/sales/delivery/**}
 * takes ONLY a yaya-sales-issued courier bearer token — never an operator JWT
 * or a service token. Register and verify stay public (the invite token and the
 * OTP are their own gates); everything else without a valid token is 401, and an
 * unknown/revoked token is 401 too — existence is never leaked.
 *
 * <p>On success the Authorization header is hidden from downstream filters so
 * the JWT resource server never tries (and fails) to decode the opaque courier
 * token as a JWT.
 */
@Component
public class CourierTokenFilter extends OncePerRequestFilter {

    public static final String COURIER_CALLER = "yaya.sales.courierCaller";

    private static final String COURIER_PATH_PREFIX = "/v1/sales/delivery/";
    private static final List<String> PUBLIC_PATHS = List.of(
            "/v1/sales/delivery/agents/register",
            "/v1/sales/delivery/agents/verify");

    private final ObjectProvider<CourierTokenVerifier> verifier;

    public CourierTokenFilter(ObjectProvider<CourierTokenVerifier> verifier) {
        this.verifier = verifier;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith(COURIER_PATH_PREFIX) || PUBLIC_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "courier token required");
            return;
        }

        Optional<Caller.Courier> courier = verifier.stream()
                .map(v -> v.verify(header.substring("Bearer ".length()).trim()))
                .flatMap(Optional::stream)
                .findFirst();
        if (courier.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "invalid courier token");
            return;
        }

        request.setAttribute(COURIER_CALLER, courier.get());
        SecurityContextHolder.getContext().setAuthentication(new CourierAuthentication(courier.get()));
        chain.doFilter(new AuthorizationHidingRequest(request), response);
    }

    static final class CourierAuthentication extends AbstractAuthenticationToken {

        private final Caller.Courier caller;

        CourierAuthentication(Caller.Courier caller) {
            super(List.of(new SimpleGrantedAuthority("ROLE_COURIER")));
            this.caller = caller;
            setAuthenticated(true);
        }

        @Override public Object getCredentials() { return ""; }
        @Override public Object getPrincipal() { return caller; }
    }

    static final class AuthorizationHidingRequest extends HttpServletRequestWrapper {

        AuthorizationHidingRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                    ? Collections.emptyEnumeration() : super.getHeaders(name);
        }
    }
}
