package com.yayatechandinnovations.yayaagentic.profile.resolvers;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.IdentityProfileMappingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.IdentityProfileMappingRepository;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks in the three claim-shape conventions {@link IdentityProfileResolver}
 * accepts: scalar equality, list containment, and the OIDC-style
 * space-separated string. Priority order = first match wins.
 */
class IdentityProfileResolverTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("lottery-dr");

    private final IdentityProfileMappingRepository repo = mock(IdentityProfileMappingRepository.class);
    private final IdentityProfileResolver resolver = new IdentityProfileResolver(repo);

    @Test
    void scalar_claim_matches_by_equality() {
        givenMappings(mapping("role", "PLAYER", "retail-player", 1, 100));
        Principal player = principal(Map.of("role", "PLAYER"));

        Optional<Ids.ProfileId> hit = resolver.resolve(req(), authCtx(), player);

        assertThat(hit).contains(new Ids.ProfileId("retail-player", 1));
    }

    @Test
    void list_claim_matches_by_containment() {
        givenMappings(mapping("groups", "CASHIER", "retail-cashier", 1, 100));
        Principal cashier = principal(Map.of("groups", List.of("EMPLOYEE", "CASHIER")));

        Optional<Ids.ProfileId> hit = resolver.resolve(req(), authCtx(), cashier);

        assertThat(hit).contains(new Ids.ProfileId("retail-cashier", 1));
    }

    @Test
    void space_separated_claim_matches_each_token() {
        // OIDC `scope` claim convention: space-separated string.
        givenMappings(mapping("scope", "PLAYER_BETTING", "retail-player", 1, 100));
        Principal player = principal(Map.of("scope", "openid PLAYER_QUERY_PUBLIC PLAYER_BETTING"));

        Optional<Ids.ProfileId> hit = resolver.resolve(req(), authCtx(), player);

        assertThat(hit).contains(new Ids.ProfileId("retail-player", 1));
    }

    @Test
    void first_priority_match_wins() {
        // Repo returns rows already ordered by priority ASC; verify the resolver
        // honours that order instead of taking the last match.
        givenMappings(
                mapping("role", "ADMIN", "admin-finance", 1, 10),    // priority 10
                mapping("role", "ADMIN", "admin-readonly", 1, 50));  // priority 50, never reached
        Principal admin = principal(Map.of("role", "ADMIN"));

        Optional<Ids.ProfileId> hit = resolver.resolve(req(), authCtx(), admin);

        assertThat(hit).contains(new Ids.ProfileId("admin-finance", 1));
    }

    @Test
    void no_principal_yields_empty() {
        givenMappings(mapping("role", "PLAYER", "retail-player", 1, 100));

        Optional<Ids.ProfileId> hit = resolver.resolve(req(), authCtx(), null);

        assertThat(hit).isEmpty();
    }

    @Test
    void unknown_claim_value_yields_empty_so_chain_continues() {
        givenMappings(mapping("role", "PLAYER", "retail-player", 1, 100));
        Principal guest = principal(Map.of("role", "GUEST"));

        Optional<Ids.ProfileId> hit = resolver.resolve(req(), authCtx(), guest);

        assertThat(hit).isEmpty();
    }

    // ---- Helpers ------------------------------------------------------------

    private void givenMappings(IdentityProfileMappingEntity... rows) {
        when(repo.findByTenantIdOrderByPriorityAscIdAsc(eq(TENANT.value())))
                .thenReturn(List.of(rows));
    }

    private static IdentityProfileMappingEntity mapping(String claim, String value,
                                                        String profile, int version, int priority) {
        return new IdentityProfileMappingEntity(
                TENANT.value(), claim, value, profile, version, priority);
    }

    private static Principal principal(Map<String, Object> claims) {
        return new Principal("user-1", TENANT, Set.of(), claims, Instant.now());
    }

    private static StartConversationRequest req() {
        return new StartConversationRequest(TENANT, Optional.empty(), "web", Map.of());
    }

    private static AuthContext authCtx() {
        return new AuthContext(TENANT, Map.of(), Optional.empty());
    }
}
