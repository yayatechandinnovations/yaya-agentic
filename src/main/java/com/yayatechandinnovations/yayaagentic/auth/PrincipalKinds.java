package com.yayatechandinnovations.yayaagentic.auth;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantJwtSecretRepository;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What kind of caller a verified principal is, for the door check a profile
 * declares in {@code metadata.principalKinds}.
 *
 * <p>A kind is never read from the token: it is the label the tenant's own
 * trust configuration gives to the issuer that verified it
 * ({@code tenant_jwt_secrets.principal_kind}). A principal no configured issuer
 * vouches for — an anonymous one included — has no kinds, so it is admitted
 * only by profiles that declare none.
 */
@Component
public class PrincipalKinds {

    public static final String PROFILE_METADATA_KEY = "principalKinds";

    private static final com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, String>> PINS =
            new com.fasterxml.jackson.core.type.TypeReference<>() {};

    private final TenantJwtSecretRepository trust;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public PrincipalKinds(TenantJwtSecretRepository trust,
                          com.fasterxml.jackson.databind.ObjectMapper json) {
        this.trust = trust;
        this.json = json;
    }

    public Set<String> kindsOf(Ids.TenantId tenant, Principal principal) {
        if (tenant == null || principal == null || principal.claims() == null) return Set.of();
        Object iss = principal.claims().get("iss");
        if (iss == null) return Set.of();
        Set<String> kinds = new LinkedHashSet<>();
        for (TenantJwtSecretEntity row : trust.findByTenantId(tenant.value())) {
            if (row.getPrincipalKind() != null && row.getIss().equals(String.valueOf(iss))
                    && pinnedTo(row, principal)) {
                kinds.add(row.getPrincipalKind());
            }
        }
        return kinds;
    }

    /**
     * The issuer's claim pins hold for this principal. Re-checked here (the
     * authenticator already enforced them) so a principal that reached the
     * engine another way cannot borrow the label by carrying the same
     * {@code iss}. An unpinned or unreadable row grants nothing.
     */
    private boolean pinnedTo(TenantJwtSecretEntity row, Principal principal) {
        String raw = row.getRequiredClaimsJson();
        if (raw == null || raw.isBlank()) return false;
        try {
            java.util.Map<String, String> pins = json.readValue(raw, PINS);
            if (pins == null || pins.isEmpty()) return false;
            for (var pin : pins.entrySet()) {
                Object actual = principal.claims().get(pin.getKey());
                if (actual == null || !pin.getValue().equals(String.valueOf(actual))) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The kinds a profile admits; empty = it declares no door check. */
    public static List<String> declaredBy(Profile profile) {
        Object raw = profile == null || profile.metadata() == null
                ? null : profile.metadata().get(PROFILE_METADATA_KEY);
        if (!(raw instanceof Collection<?> c)) return List.of();
        return c.stream().filter(o -> o != null && !String.valueOf(o).isBlank())
                .map(String::valueOf).toList();
    }

    public boolean admits(Profile profile, Principal principal) {
        List<String> declared = declaredBy(profile);
        if (declared.isEmpty()) return true;
        Set<String> have = kindsOf(profile.tenant(), principal);
        return declared.stream().anyMatch(have::contains);
    }
}
