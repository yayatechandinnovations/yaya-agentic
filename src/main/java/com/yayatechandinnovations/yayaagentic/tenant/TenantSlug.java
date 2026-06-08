package com.yayatechandinnovations.yayaagentic.tenant;

import com.yayatechandinnovations.yayaagentic.api.AdminApiException;

import java.util.regex.Pattern;

/**
 * Tenant id grammar per tenant-registry-design §3.1.
 * Lowercase alphanum + hyphen, 3..64 chars, must start and end with alphanum.
 */
public final class TenantSlug {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$");

    private TenantSlug() {}

    public static void validate(String slug) {
        if (slug == null || slug.isBlank()) {
            throw AdminApiException.badRequest("bad_tenant_id", "tenant id is required");
        }
        if (!SLUG.matcher(slug).matches()) {
            throw AdminApiException.badRequest("bad_tenant_id",
                    "tenant id must match [a-z0-9][a-z0-9-]{1,62}[a-z0-9] (got: " + slug + ")");
        }
    }
}
