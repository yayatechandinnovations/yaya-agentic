package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Session;

import java.util.List;
import java.util.Map;

/**
 * <p>{@code clientTraits} are the profile's {@code extensions} surfaced verbatim
 * at session start (a map of namespaced <i>kind</i> → opaque payload). They let a
 * client render app-specific chrome the moment a chat opens, without a new
 * transport — e.g. a {@code sales.store} trait lights up a store's affordances.
 * The framework never interprets the payloads; the owning app (yaya-sales) defines
 * them and serves the data behind the HTTP seam (yaya-sales design §11.4/§12.3).</p>
 */
public record StartSessionResult(
        Session session,
        Ids.ProfileId profile,
        String greeting,
        List<String> quickReplies,
        Map<String, Object> clientTraits
) {
    /** Back-compat: a session with no client traits. */
    public StartSessionResult(Session session, Ids.ProfileId profile,
                              String greeting, List<String> quickReplies) {
        this(session, profile, greeting, quickReplies, Map.of());
    }
}
