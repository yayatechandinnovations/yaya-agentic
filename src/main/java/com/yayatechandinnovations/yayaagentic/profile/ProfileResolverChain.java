package com.yayatechandinnovations.yayaagentic.profile;

import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class ProfileResolverChain {

    private final List<ProfileResolver> resolvers;

    public ProfileResolverChain(List<ProfileResolver> resolvers) {
        this.resolvers = resolvers.stream()
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .toList();
    }

    public Optional<Ids.ProfileId> resolve(StartConversationRequest req, AuthContext auth) {
        for (ProfileResolver r : resolvers) {
            Optional<Ids.ProfileId> hit = r.resolve(req, auth);
            if (hit.isPresent()) return hit;
        }
        return Optional.empty();
    }
}
