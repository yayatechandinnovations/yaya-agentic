package com.yayatechandinnovations.yayaagentic.profile.resolvers;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.profile.ProfileResolver;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Maps a verified principal's claims to a profile (e.g. {@code role=merchant}
 * → {@code merchant-agent}). Concrete claim→profile mapping is intentionally
 * left as a configuration concern, wired in M1.
 */
@Component
@Order(20)
public class IdentityProfileResolver implements ProfileResolver {
    @Override
    public Optional<Ids.ProfileId> resolve(StartConversationRequest req, AuthContext auth) {
        return Optional.empty();
    }
}
