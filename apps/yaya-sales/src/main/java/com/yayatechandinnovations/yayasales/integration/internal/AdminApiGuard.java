package com.yayatechandinnovations.yayasales.integration.internal;

import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.auth.SalesAuthProperties;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import com.yayatechandinnovations.yayasales.stores.StoreView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * §15 trust boundary for {@code /v1/sales/admin/**}: the admin plane is
 * OPERATOR-authenticated (§10.1), and in a shared deployment an operator may
 * only act on stores of the TENANT their token belongs to. The operator's
 * tenant(s) come from a configurable JWT claim
 * ({@code yaya.sales.auth.operator-tenant-claim}, default {@code org} — what
 * yaya-identity mints); the claim value must equal the store's tenant id (the
 * same id the agent plane's service tokens are keyed by).
 *
 * <p>Same status for "store missing" and "not your tenant's store" — existence
 * is not leaked (mirrors {@link AgentApiGuard}). Service and courier tokens
 * are rejected here outright: they are different caller classes (§10).
 *
 * <p>Escape hatch for single-merchant deployments whose issuer mints no tenant
 * claim: {@code yaya.sales.auth.enforce-operator-tenant=false}.
 */
@Component
public class AdminApiGuard implements HandlerInterceptor {

    public static final String STORE_HEADER = "X-Yaya-Store-Id";

    private final StoreDirectory stores;
    private final SalesAuthProperties props;

    public AdminApiGuard(StoreDirectory stores, SalesAuthProperties props) {
        this.stores = stores;
        this.props = props;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (!props.enforceOperatorTenant()) {
            return true;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) {
            // A service or courier token is valid credential — for a DIFFERENT plane.
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "admin API requires an operator token");
            return false;
        }

        Set<String> tenants = tenantsOf(jwt);
        if (tenants.isEmpty()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "operator token carries no '" + props.operatorTenantClaim()
                            + "' claim — set yaya.sales.auth.operator-tenant-claim to the claim "
                            + "your issuer mints, or disable yaya.sales.auth.enforce-operator-tenant");
            return false;
        }
        com.yayatechandinnovations.yayasales.platform.auth.OperatorTenants.stash(request, tenants);

        String storeId = request.getHeader(STORE_HEADER);
        if (storeId != null && !storeId.isBlank()) {
            StoreView store = stores.store(new StoreId(storeId)).orElse(null);
            if (store == null || !tenants.contains(store.tenant().value())) {
                // Same status for "missing" and "not yours": existence is not leaked.
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "store is not accessible");
                return false;
            }
        }
        return true;
    }

    private Set<String> tenantsOf(JwtAuthenticationToken jwt) {
        Object claim = jwt.getToken().getClaim(props.operatorTenantClaim());
        if (claim instanceof String s && !s.isBlank()) {
            return Set.of(s);
        }
        if (claim instanceof List<?> list) {
            return list.stream().map(String::valueOf)
                    .filter(v -> !v.isBlank())
                    .collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }

    @Configuration
    static class Registration implements WebMvcConfigurer {

        private final AdminApiGuard guard;

        Registration(AdminApiGuard guard) {
            this.guard = guard;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(guard).addPathPatterns("/v1/sales/admin/**");
        }
    }
}
