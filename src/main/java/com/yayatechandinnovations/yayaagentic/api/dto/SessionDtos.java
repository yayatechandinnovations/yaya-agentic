package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.auth.playground.ActAs;
import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.List;
import java.util.Map;

public final class SessionDtos {

    private SessionDtos() {}

    public record StartSessionRequest(String tenant, String profileId, Integer profileVersion,
                                      String channel, Map<String, Object> hints, ActAs actAs) {}

    /** {@code traits} are the profile's client traits (e.g. its {@code menu}), as on the chat API. */
    public record StartSessionResponse(String sessionId, String profileId, int profileVersion,
                                       String greeting, List<String> quickReplies,
                                       Map<String, Object> traits) {
        public static StartSessionResponse of(Ids.SessionId sessionId, Ids.ProfileId profileId,
                                              String greeting, List<String> quickReplies) {
            return of(sessionId, profileId, greeting, quickReplies, Map.of());
        }

        public static StartSessionResponse of(Ids.SessionId sessionId, Ids.ProfileId profileId,
                                              String greeting, List<String> quickReplies,
                                              Map<String, Object> traits) {
            return new StartSessionResponse(sessionId.value(), profileId.value(), profileId.version(),
                    greeting, quickReplies, traits == null ? Map.of() : traits);
        }
    }

    /**
     * {@code selection} answers a frame the engine showed (confirm / cancel by
     * {@code callId}), exactly as on the chat API. {@code clientContext} is what
     * the user's app has open — {@code screenId}, {@code recordRef},
     * {@code label} — given to the model for this turn only, as unverified
     * context.
     */
    public record MessageRequest(String text, Selection selection, Map<String, Object> clientContext) {
        public MessageRequest(String text) {
            this(text, null, null);
        }
    }

    /**
     * {@code value} is only read for {@code kind == "secret"}: what the user
     * typed into a secret prompt (§15 of the guide); empty asks for a new code.
     * It is never printed.
     */
    public record Selection(String kind, String code, String callId, List<String> itemIds, String value) {
        public Selection(String kind, String code, String callId, List<String> itemIds) {
            this(kind, code, callId, itemIds, null);
        }

        @Override
        public String toString() {
            return "Selection[kind=" + kind + ", callId=" + callId + "]";
        }
    }

    /** One display turn of a reopened conversation. {@code uiHints} are the rich
     *  frames the turn showed, attachment links freshly signed. */
    public record HistoryTurn(String role, String text, String createdAt,
                              List<Map<String, Object>> uiHints) {}

    /**
     * A tool call that ran and whose result is stored: how a client that
     * stopped a turn learns whether a change it had in flight was made. A
     * call that was only told to wait for another card ({@code deferred}) did
     * not run and is not listed.
     * {@code status} is {@code OK}, {@code DENIED} or {@code FAILED}.
     */
    public record ToolOutcome(String callId, String tool, String status, String createdAt) {}

    /**
     * The caller's most recent session on a profile and how to treat it:
     * {@code RESUMABLE} (keep sending to {@code sessionId}), {@code EXPIRED}
     * (shown for reference; start a new session) or {@code NONE}.
     * {@code pendingConfirm} is the confirm frame still waiting for an answer;
     * {@code pendingSecret} is the secret prompt still waiting for one;
     * {@code pendingPlan} is the plan frame still waiting for one (§16 of the
     * guide) — at most one of the three is set;
     * {@code lastToolResults} are the tool calls that completed since the
     * user's last stored message.
     */
    public record LatestSessionResponse(String state, String sessionId, String profileId,
                                        Integer profileVersion, String createdAt, String lastActivityAt,
                                        List<HistoryTurn> turns, Map<String, Object> pendingConfirm,
                                        Map<String, Object> pendingSecret,
                                        Map<String, Object> pendingPlan,
                                        List<ToolOutcome> lastToolResults) {
        public static LatestSessionResponse none() {
            return new LatestSessionResponse("NONE", null, null, null, null, null, List.of(), null, null,
                    null, List.of());
        }
    }

    public record HistoryResponse(String sessionId, List<HistoryTurn> turns,
                                  Map<String, Object> pendingConfirm,
                                  Map<String, Object> pendingSecret,
                                  Map<String, Object> pendingPlan,
                                  List<ToolOutcome> lastToolResults) {}

    /** What the caller's tenant has used of its monthly allowance (null = unlimited). */
    public record UsageResponse(String month, long messages, Integer monthlyMessages) {}

    /** Client-initiated action: run one whitelisted tool by id (two doors, one lock, §9). */
    public record ActionRequest(String tool, Map<String, Object> args) {}
}
