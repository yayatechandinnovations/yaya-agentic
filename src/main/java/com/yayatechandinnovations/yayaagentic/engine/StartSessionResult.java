package com.yayatechandinnovations.yayaagentic.engine;

import com.yayatechandinnovations.yayaagentic.commerce.CommerceConfig;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Session;

import java.util.List;

/**
 * <p>{@code commerce} is how the client discovers a chat is a store at session
 * start (nullable — null for non-commerce profiles). It renders the persistent
 * cart affordance the moment a commerce chat opens, without a new transport. See
 * {@code ecommerce-chat-design.md} §3.</p>
 */
public record StartSessionResult(
        Session session,
        Ids.ProfileId profile,
        String greeting,
        List<String> quickReplies,
        CommerceConfig commerce
) {
    /** Back-compat: a non-commerce session. */
    public StartSessionResult(Session session, Ids.ProfileId profile,
                              String greeting, List<String> quickReplies) {
        this(session, profile, greeting, quickReplies, null);
    }
}
