package com.yayatechandinnovations.yayasales.platform.auth;

import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Set;

/**
 * The per-request contract between the admin-plane guard (which resolves the
 * operator's tenant claim) and the few endpoints whose tenant scope rides the
 * request body/params instead of the store header (store creation, tenant-wide
 * store listing). Lives in platform so any module's controller can consult it
 * without reaching into another module's internals.
 */
public final class OperatorTenants {

    public static final String ATTRIBUTE = "yaya.sales.operatorTenants";

    private OperatorTenants() {}

    public static void stash(HttpServletRequest request, Set<String> tenants) {
        request.setAttribute(ATTRIBUTE, tenants);
    }

    /** No-op when enforcement is off (nothing was stashed). */
    public static void requireAllowed(HttpServletRequest request, String tenantId) {
        Object stashed = request.getAttribute(ATTRIBUTE);
        if (stashed instanceof Set<?> tenants && !tenants.contains(tenantId)) {
            throw new ApiErrors.ForbiddenException(
                    "operator token does not belong to tenant " + tenantId);
        }
    }
}
