package com.yayatechandinnovations.yayaagentic.startflow;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * SF-0 resolver. Precedence (first non-blank wins):
 * <ol>
 *   <li><b>Channel binding</b> — {@code hints["channel.startFlowId"]}, set by the
 *       channel start path from the sender's / entry point's {@code start_flow_id}
 *       column.</li>
 *   <li><b>Profile default</b> — {@code profile.defaultStartFlowId()}
 *       ({@code profiles.default_start_flow_id}).</li>
 * </ol>
 * A channel {@code hints["channel.startFlowVersion"]} pins a version; absent ⇒
 * latest. See {@code docs/design/session-start-flows.md} §6.
 */
@Component
public class DefaultStartFlowResolver implements StartFlowResolver {

    @Override
    public Optional<Ids.StartFlowId> resolve(StartConversationRequest req, Profile profile, Principal principal) {
        Map<String, Object> hints = req == null || req.hints() == null ? Map.of() : req.hints();

        // 1. Channel binding wins — surfaced through hints by the channel layer.
        String channelId = firstNonBlank(str(hints.get("channel.startFlowId")), str(hints.get("startFlowId")));
        if (channelId != null) {
            Integer version = intOrNull(firstNonBlank(
                    str(hints.get("channel.startFlowVersion")), str(hints.get("startFlowVersion"))));
            return Optional.of(version == null ? Ids.StartFlowId.latest(channelId) : new Ids.StartFlowId(channelId, version));
        }

        // 2. Profile default.
        if (profile != null && profile.defaultStartFlowId() != null) {
            return Optional.of(profile.defaultStartFlowId());
        }
        return Optional.empty();
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isBlank() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static Integer intOrNull(String s) {
        if (s == null) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
