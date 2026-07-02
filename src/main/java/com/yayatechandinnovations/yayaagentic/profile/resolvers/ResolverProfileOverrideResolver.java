package com.yayatechandinnovations.yayaagentic.profile.resolvers;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthIntegrator;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.profile.ProfileResolver;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Honors a profile the tenant's auth resolver selected for this caller
 * (VIP tiering, staff-vs-customer, account-state routing). The selection
 * arrives as the reserved {@link ChannelAuthIntegrator#RESERVED_PROFILE_CLAIM}
 * claim, which the integrator stamps post-verification ONLY from the resolver's
 * structured return AND only after passing the §7 guardrails (published +
 * in-tenant + per-surface allowlist). This resolver therefore trusts the claim:
 * a resolver cannot reach it by minting a JWT body claim, and an invalid
 * override never gets stamped in the first place.
 *
 * <p>Ordered at {@code 15} — above {@link IdentityProfileResolver} (20) and
 * {@link ChannelProfileResolver} (30) so a resolver override beats the bound
 * profile, but below {@link ExplicitProfileResolver} (10) so an operator's
 * deliberate "act-as this profile" pick still wins.
 *
 * <p>See {@code docs/design/resolver-profile-override-design.md} §6, §8.
 */
@Component
@Order(15)
public class ResolverProfileOverrideResolver implements ProfileResolver {

    @Override
    public Optional<Ids.ProfileId> resolve(StartConversationRequest req,
                                           AuthContext auth,
                                           Principal principal) {
        if (principal == null || principal.claims() == null) return Optional.empty();
        Object key = principal.claims().get(ChannelAuthIntegrator.RESERVED_PROFILE_CLAIM);
        if (!(key instanceof String profileKey) || profileKey.isBlank()) return Optional.empty();

        Integer version = versionOf(
                principal.claims().get(ChannelAuthIntegrator.RESERVED_PROFILE_VERSION_CLAIM));
        // Null version = unpinned: engine.start resolves it to the latest
        // published version, exactly as IdentityProfileResolver does (§5.2).
        return Optional.of(version == null
                ? Ids.ProfileId.latest(profileKey)
                : new Ids.ProfileId(profileKey, version));
    }

    private static Integer versionOf(Object raw) {
        return switch (raw) {
            case null -> null;
            case Number n -> n.intValue();
            case String s when !s.isBlank() -> tryParse(s);
            default -> null;
        };
    }

    private static Integer tryParse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;   // an unparseable version pins nothing → latest
        }
    }
}
