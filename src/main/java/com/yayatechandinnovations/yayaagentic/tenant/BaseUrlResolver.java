package com.yayatechandinnovations.yayaagentic.tenant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;

/**
 * Dispatch-time joiner that turns a path-only {@code urlTemplate} (§6.1) into
 * an absolute URL for {@code HttpToolDispatcher}. Pairs with the save-time
 * {@link BaseUrlValidator#validatePathOnlyTemplate} — the validator guarantees
 * the stored template is a path; this resolver supplies the host.
 *
 * <p>Two host sources, in order:
 * <ol>
 *   <li>An optional {@code X-Yaya-Host-Base-Url} override, glob-matched against
 *       the tenant's {@code host_base_url_allowlist}. The allowlist entries are
 *       full URL patterns (per {@link BaseUrlValidator#validateUrlPattern}),
 *       so we glob-match the override URL verbatim — we do NOT collapse to
 *       origin (that's what {@link OriginEnforcer} does for inbound traffic;
 *       different surface).</li>
 *   <li>Otherwise the tenant's {@code host_base_url}.</li>
 * </ol>
 *
 * <p>Override is <em>additive</em> to the system-level
 * {@code yaya.agentic.http-tools.egress-allowlist}: the dispatcher still runs
 * {@code HttpEgressPolicy.check} on the resolved URL. The override only widens
 * what the tenant trusts, not what the platform trusts.</p>
 */
@Component
public class BaseUrlResolver {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final TenantRepository tenants;
    private final ObjectMapper json;

    public BaseUrlResolver(TenantRepository tenants, ObjectMapper json) {
        this.tenants = tenants;
        this.json = json;
    }

    /**
     * @param tenantId tenant whose host base to use
     * @param renderedPath the rendered (post-substitution) path; must start with {@code /}
     * @param overrideHostBaseUrl optional {@code X-Yaya-Host-Base-Url} value from the inbound request
     * @return absolute URI ready for {@code HttpEgressPolicy.check}
     */
    public URI resolve(Ids.TenantId tenantId,
                       String renderedPath,
                       String overrideHostBaseUrl) {
        if (tenantId == null || tenantId.value() == null || tenantId.value().isBlank()) {
            throw new BaseUrlResolutionException("missing tenant on execution context");
        }
        String path = validateRenderedPath(renderedPath);

        TenantEntity tenant = tenants.findById(tenantId.value())
                .orElseThrow(() -> new BaseUrlResolutionException(
                        "unknown tenant '" + tenantId.value() + "'"));

        String base = effectiveBase(tenant, overrideHostBaseUrl);
        return join(base, path);
    }

    // ---- Internals ------------------------------------------------------

    private static String validateRenderedPath(String renderedPath) {
        if (renderedPath == null || renderedPath.isBlank()) {
            throw new BaseUrlResolutionException("rendered url path is empty (unfilled placeholder?)");
        }
        if (containsControlChars(renderedPath)) {
            throw new BaseUrlResolutionException("rendered url path contains control characters");
        }
        if (renderedPath.startsWith("//")) {
            throw new BaseUrlResolutionException("rendered url path must not be protocol-relative");
        }
        if (!renderedPath.startsWith("/")) {
            throw new BaseUrlResolutionException("rendered url path must start with '/'");
        }
        return renderedPath;
    }

    private String effectiveBase(TenantEntity tenant, String overrideHostBaseUrl) {
        if (overrideHostBaseUrl != null && !overrideHostBaseUrl.isBlank()) {
            String override = overrideHostBaseUrl.trim();
            List<String> allowlist = readAllowlist(tenant.getHostBaseUrlAllowlistJson());
            for (String pattern : allowlist) {
                if (GlobMatcher.matches(pattern, override)) {
                    return override;
                }
            }
            throw new BaseUrlResolutionException(
                    "X-Yaya-Host-Base-Url override '" + override
                            + "' not in tenant allowlist");
        }
        String tenantBase = tenant.getHostBaseUrl();
        if (tenantBase == null || tenantBase.isBlank()) {
            throw new BaseUrlResolutionException(
                    "tenant '" + tenant.getId() + "' has no host_base_url configured");
        }
        return tenantBase;
    }

    private List<String> readAllowlist(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<String> parsed = json.readValue(raw, STRING_LIST);
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Joins {@code base} and {@code path} so any path prefix on the base is
     * preserved (e.g. {@code https://api.example.com/api/v2} + {@code /orders/1}
     * → {@code https://api.example.com/api/v2/orders/1}). {@link URI#resolve}
     * alone strips the base path when the base has no trailing slash, so this
     * does explicit concatenation on raw components.
     */
    private static URI join(String base, String path) {
        URI baseUri;
        try {
            baseUri = new URI(base);
        } catch (URISyntaxException e) {
            throw new BaseUrlResolutionException(
                    "host_base_url is not a valid URI: " + base);
        }
        if (baseUri.getScheme() == null || baseUri.getHost() == null) {
            throw new BaseUrlResolutionException(
                    "host_base_url missing scheme/host: " + base);
        }
        String basePath = Optional.ofNullable(baseUri.getRawPath()).orElse("");
        if (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        String absolute = baseUri.getScheme() + "://"
                + baseUri.getRawAuthority()
                + basePath + path;
        try {
            return new URI(absolute);
        } catch (URISyntaxException e) {
            throw new BaseUrlResolutionException(
                    "joined URL is not a valid URI: " + absolute);
        }
    }

    private static boolean containsControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) return true;
        }
        return false;
    }
}
