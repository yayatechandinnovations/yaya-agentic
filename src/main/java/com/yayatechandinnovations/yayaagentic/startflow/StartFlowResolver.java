package com.yayatechandinnovations.yayaagentic.startflow;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;

import java.util.Optional;

/**
 * Resolves which {@link StartFlow} (if any) applies to a starting session.
 * Mirrors {@code ProfileResolver}. Precedence: channel binding → profile
 * default → none. See {@code docs/design/session-start-flows.md} §6.
 */
public interface StartFlowResolver {

    Optional<Ids.StartFlowId> resolve(StartConversationRequest req, Profile profile, Principal principal);
}
