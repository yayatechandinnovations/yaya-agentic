package com.yayatechandinnovations.yayaagentic.profile.resolvers;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.profile.ProfileResolver;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Last-resort resolver. Returns the tenant's configured default profile, or empty. */
@Component
@Order(1_000)
public class FallbackProfileResolver implements ProfileResolver {
    @Override
    public Optional<Ids.ProfileId> resolve(StartConversationRequest req, AuthContext auth) {
        return Optional.empty();
    }
}
