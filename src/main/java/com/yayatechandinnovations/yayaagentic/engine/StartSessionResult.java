package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Session;
import com.yayatechandinnovations.yayaagentic.profile.CommerceBinding;

import java.util.List;

/**
 * <p>{@code commerce} is how the client discovers a chat is a store at session
 * start (nullable — null for non-commerce profiles). It renders the persistent
 * cart affordance the moment a store chat opens, without a new transport. The
 * catalog data itself is fetched via {@code sales.*} HTTP tools in yaya-sales
 * (yaya-sales design §12.3).</p>
 */
public record StartSessionResult(
        Session session,
        Ids.ProfileId profile,
        String greeting,
        List<String> quickReplies,
        CommerceBinding commerce
) {
    /** Back-compat: a non-commerce session. */
    public StartSessionResult(Session session, Ids.ProfileId profile,
                              String greeting, List<String> quickReplies) {
        this(session, profile, greeting, quickReplies, null);
    }
}
