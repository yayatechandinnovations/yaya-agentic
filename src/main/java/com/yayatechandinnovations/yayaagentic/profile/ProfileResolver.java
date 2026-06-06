package com.yayatechandinnovations.yayaagentic.profile;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.Optional;

/**
 * Pluggable resolver that maps an incoming session-start request to a profile.
 * Resolvers are tried in order by {@link ProfileResolverChain}; first non-empty wins.
 * See design §5.6.
 */
public interface ProfileResolver {
    Optional<Ids.ProfileId> resolve(StartConversationRequest req, AuthContext auth);
}
