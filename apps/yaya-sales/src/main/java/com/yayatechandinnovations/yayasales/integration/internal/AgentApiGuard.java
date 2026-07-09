package com.yayatechandinnovations.yayasales.integration.internal;

import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.auth.Caller;
import com.yayatechandinnovations.yayasales.platform.auth.ServiceTokenFilter;
import com.yayatechandinnovations.yayasales.stores.StoreDirectory;
import com.yayatechandinnovations.yayasales.stores.StoreView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * §15 trust boundary for {@code /v1/sales/agent/**}: a valid SERVICE token is
 * REQUIRED (a shopper JWT alone is not enough — the platform proves the call
 * went through schema validation + the Authorizer chain), and the service
 * token's tenant must OWN the acting store — tenant A's agent can never
 * operate tenant B's store.
 */
@Component
public class AgentApiGuard implements HandlerInterceptor {

    public static final String STORE_HEADER = "X-Yaya-Store-Id";
    public static final String STORE_ATTRIBUTE = "yaya.sales.agentStore";

    private final StoreDirectory stores;

    public AgentApiGuard(StoreDirectory stores) {
        this.stores = stores;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        Object attribute = request.getAttribute(ServiceTokenFilter.SERVICE_CALLER);
        if (!(attribute instanceof Caller.Service service)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                    "agent API requires a platform service token");
            return false;
        }

        String storeId = request.getHeader(STORE_HEADER);
        if (storeId == null || storeId.isBlank()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, STORE_HEADER + " header is required");
            return false;
        }
        StoreView store = stores.store(new StoreId(storeId)).orElse(null);
        if (store == null || !store.tenant().equals(service.tenant())) {
            // Same status for "missing" and "not yours": existence is not leaked.
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "store is not accessible");
            return false;
        }

        request.setAttribute(STORE_ATTRIBUTE, store);
        return true;
    }

    @Configuration
    static class Registration implements WebMvcConfigurer {

        private final AgentApiGuard guard;

        Registration(AgentApiGuard guard) {
            this.guard = guard;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(guard).addPathPatterns("/v1/sales/agent/**");
        }
    }
}
