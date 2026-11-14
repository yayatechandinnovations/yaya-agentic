package com.yayatechandinnovations.yayaagentic.tenant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.config.SessionsApiProperties;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The browser origins the session endpoints answer to: every live tenant's own
 * origin(s), plus the configured extras.
 *
 * <p>A CORS preflight names no tenant, so it can only be answered against the
 * union. That is not the tenant boundary — {@link OriginEnforcer} is: it checks
 * the real request's {@code Origin} against the one tenant it addresses and
 * refuses the request itself. This class applies the same per-tenant rule as
 * the enforcer (allowlist when set, else the host origin) so the two never
 * disagree.
 *
 * <p>The union is rebuilt at most once a minute; a new tenant's origin is
 * therefore accepted within a minute of being registered.
 */
@Component
public class TenantOrigins {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final Duration TTL = Duration.ofSeconds(60);

    private final TenantRepository tenants;
    private final ObjectMapper json;
    private final List<String> extraOrigins;

    private volatile List<String> patterns = List.of();
    private volatile long loadedAtNanos;
    private volatile boolean loaded;

    public TenantOrigins(TenantRepository tenants, ObjectMapper json, SessionsApiProperties props) {
        this.tenants = tenants;
        this.json = json;
        this.extraOrigins = props.extraOriginsOrEmpty();
    }

    /** An origin configured for every tenant. */
    public boolean isExtra(String origin) {
        return matchesAny(extraOrigins, origin);
    }

    /** An extra origin, or the origin of at least one live tenant. */
    public boolean isKnown(String origin) {
        if (origin == null || origin.isBlank()) return false;
        return isExtra(origin) || matchesAny(tenantPatterns(), origin);
    }

    /** Drop the cached union (tests; an operator-triggered refresh). */
    public void evict() {
        loaded = false;
    }

    private List<String> tenantPatterns() {
        if (loaded && System.nanoTime() - loadedAtNanos < TTL.toNanos()) return patterns;
        List<String> built = new ArrayList<>();
        for (TenantEntity tenant : tenants.findAll()) {
            if (TenantEntity.Status.ARCHIVED.name().equals(tenant.getStatus())) continue;
            List<String> allowlist = parse(tenant.getInboundOriginAllowlistJson());
            if (!allowlist.isEmpty()) {
                built.addAll(allowlist);
            } else {
                String own = BaseUrlValidator.originOf(tenant.getHostBaseUrl());
                if (own != null) built.add(own);
            }
        }
        patterns = List.copyOf(built);
        loadedAtNanos = System.nanoTime();
        loaded = true;
        return patterns;
    }

    private List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<String> list = json.readValue(raw, STRING_LIST);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean matchesAny(List<String> patterns, String origin) {
        if (origin == null) return false;
        for (String pattern : patterns) {
            if (GlobMatcher.matches(pattern, origin)) return true;
        }
        return false;
    }
}
