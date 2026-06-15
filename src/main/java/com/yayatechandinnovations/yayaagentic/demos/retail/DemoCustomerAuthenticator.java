package com.yayatechandinnovations.yayaagentic.demos.retail;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Demo authenticator for the retail-customer profile. Reads
 * {@code X-Yaya-Customer} from inbound headers and issues a Principal
 * with that subject; if the header is absent, defaults to
 * {@code cust-1} so the playground works out of the box.
 * <p>
 * Runs ahead of {@link com.yayatechandinnovations.yayaagentic.auth.dev.NoopAuthenticator}
 * in the chain — anything that isn't an anonymous-uuid principal is much
 * more useful for the ownership AuthZ demo.
 */
@Component
@Order(10)
public class DemoCustomerAuthenticator implements Authenticator {

    private static final String HEADER = "X-Yaya-Customer";
    private static final String DEFAULT_SUBJECT = "cust-1";

    @Override
    public String name() {
        return "demo-customer";
    }

    @Override
    public Optional<Principal> tryAuthenticate(AuthContext ctx) {
        // A demo authenticator must never overwrite a real credential. When
        // the caller supplied an Authorization header (Bearer/Basic), this
        // request belongs to the production chain (OidcAuthenticator); we
        // yield so the JWT-derived scopes survive instead of being replaced
        // by the hardcoded retail scopes below.
        if (hasAuthorizationHeader(ctx)) return Optional.empty();

        String subject = headerOrDefault(ctx);
        Ids.TenantId tenant = ctx.tenant() != null ? ctx.tenant() : new Ids.TenantId("default");
        return Optional.of(new Principal(
                subject,
                tenant,
                Set.of("customer.read", "customer.write"),
                Map.of("customerId", subject),
                Instant.now()));
    }

    private static boolean hasAuthorizationHeader(AuthContext ctx) {
        Map<String, String> headers = ctx.headers();
        if (headers == null) return false;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null
                    && "authorization".equalsIgnoreCase(e.getKey())
                    && e.getValue() != null
                    && !e.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String headerOrDefault(AuthContext ctx) {
        Map<String, String> headers = ctx.headers();
        if (headers == null) return DEFAULT_SUBJECT;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (HEADER.equalsIgnoreCase(e.getKey())
                    && e.getValue() != null && !e.getValue().isBlank()) {
                return e.getValue().trim().toLowerCase(Locale.ROOT);
            }
        }
        return DEFAULT_SUBJECT;
    }
}
