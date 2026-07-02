package com.yayatechandinnovations.yayaagentic.profile.resolvers;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.IdentityProfileMappingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.IdentityProfileMappingRepository;
import com.yayatechandinnovations.yayaagentic.profile.ProfileResolver;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;

/**
 * Maps a verified principal's JWT claims onto a profile via per-tenant
 * config rows in {@code identity_profile_mappings}. HTTP consumers can
 * keep their client code domain-neutral: the lottery app (or any other
 * caller) just sends the JWT; the table answers "for tenant T, a principal
 * whose {@code role} claim is {@code PLAYER} gets the {@code retail-player}
 * profile."
 *
 * <p>Walked in priority order (ASC, then by insertion id). First match
 * wins. No row matches → returns empty and the chain falls through to
 * {@code ChannelProfileResolver} / {@code FallbackProfileResolver}.</p>
 */
@Component
@Order(20)
public class IdentityProfileResolver implements ProfileResolver {

    private final IdentityProfileMappingRepository mappings;

    public IdentityProfileResolver(IdentityProfileMappingRepository mappings) {
        this.mappings = mappings;
    }

    @Override
    public Optional<Ids.ProfileId> resolve(StartConversationRequest req, AuthContext auth, Principal principal) {
        if (principal == null || principal.claims() == null || principal.claims().isEmpty()) {
            return Optional.empty();
        }
        if (req == null || req.tenant() == null) return Optional.empty();

        var rows = mappings.findByTenantIdOrderByPriorityAscIdAsc(req.tenant().value());
        if (rows.isEmpty()) return Optional.empty();

        for (IdentityProfileMappingEntity row : rows) {
            Object claimValue = principal.claims().get(row.getClaimName());
            // OIDC issuers sometimes ship a `scope` claim as a space-separated
            // string but a `roles`/`groups` claim as a list — we accept both
            // shapes so config doesn't have to know which it is.
            if (claimMatches(claimValue, row.getClaimValue())) {
                // Null version = unpinned: resolve to the latest published
                // version at session start. A concrete value pins it (§5.2).
                Integer pinned = row.getProfileVersion();
                return Optional.of(pinned == null
                        ? Ids.ProfileId.latest(row.getProfileId())
                        : new Ids.ProfileId(row.getProfileId(), pinned));
            }
        }
        return Optional.empty();
    }

    private static boolean claimMatches(Object claimValue, String expected) {
        if (claimValue == null || expected == null) return false;
        if (claimValue instanceof Collection<?> coll) {
            for (Object item : coll) {
                if (item != null && expected.equals(item.toString())) return true;
            }
            return false;
        }
        String asString = claimValue.toString();
        if (asString.equals(expected)) return true;
        // Space-separated multi-value form (the OIDC `scope` convention).
        if (asString.indexOf(' ') >= 0) {
            for (String token : asString.split("\\s+")) {
                if (expected.equals(token)) return true;
            }
        }
        return false;
    }
}
