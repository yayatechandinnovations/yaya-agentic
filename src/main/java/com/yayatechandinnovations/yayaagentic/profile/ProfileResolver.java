package com.yayatechandinnovations.yayaagentic.profile;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;

import java.util.Optional;

/**
 * Pluggable resolver that maps an incoming session-start request to a profile.
 * Resolvers are tried in order by {@link ProfileResolverChain}; first non-empty wins.
 * See design §5.6.
 *
 * <p>Implementations receive both the raw {@link AuthContext} (headers, tenant
 * hint, channel) and — when the engine successfully authenticated the caller —
 * the verified {@link Principal} whose claims drive identity-based routing.
 * {@code principal} may be {@code null} if anonymous access is allowed and
 * no authenticator recognised the request; resolvers that depend on claims
 * should yield ({@code Optional.empty()}) in that case.</p>
 */
public interface ProfileResolver {

    /**
     * Resolve a profile for this session-start request.
     *
     * @param req       the start request — carries tenant, explicit profile hint, channel, free-form hints
     * @param auth      the inbound auth context (headers, tenant)
     * @param principal the verified caller, or {@code null} when authentication produced no principal
     */
    Optional<Ids.ProfileId> resolve(StartConversationRequest req, AuthContext auth, Principal principal);
}
