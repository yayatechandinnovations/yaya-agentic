package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.auth.playground.ActAs;
import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.List;
import java.util.Map;

public final class SessionDtos {

    private SessionDtos() {}

    public record StartSessionRequest(String tenant, String profileId, Integer profileVersion,
                                      String channel, Map<String, Object> hints, ActAs actAs) {}

    public record StartSessionResponse(String sessionId, String profileId, int profileVersion,
                                       String greeting, List<String> quickReplies) {
        public static StartSessionResponse of(Ids.SessionId sessionId, Ids.ProfileId profileId,
                                              String greeting, List<String> quickReplies) {
            return new StartSessionResponse(sessionId.value(), profileId.value(), profileId.version(),
                    greeting, quickReplies);
        }
    }

    public record MessageRequest(String text) {}

    /** Client-initiated action: run one whitelisted tool by id (two doors, one lock, §9). */
    public record ActionRequest(String tool, Map<String, Object> args) {}
}
