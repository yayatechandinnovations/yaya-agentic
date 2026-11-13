package com.yayatechandinnovations.yayaagentic.engine.action;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Store of the action buttons a proactive message offered
 * (whatsapp-ops-integration-design.md §6.4). Each row is an unguessable,
 * single-use, expiring capability bound to its recipient; the engine resolves
 * a tap through it so a button never needs the conversation it came from.
 * Implemented by the outreach subsystem, which registers the rows at delivery.
 */
public interface BoundActionStore {

    Optional<BoundAction> find(Ids.TenantId tenant, String actionId);

    /** Atomically mark the action consumed; false when someone already did. */
    boolean claim(Ids.TenantId tenant, String actionId, String consumedBy);

    /** Store the outcome of the consumed action so a repeat tap replays it. */
    void complete(Ids.TenantId tenant, String actionId, Object result);

    /** Undo a claim whose dispatch never produced a result, so the button works again. */
    void release(Ids.TenantId tenant, String actionId);

    enum Kind { TOOL, SEND }

    record Ask(String arg, String prompt, List<String> suggestions) {
        public Ask {
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        }
    }

    /**
     * @param ownerSubject   principal subject the message was addressed to
     * @param recipientPhone verified phone the message was delivered to, if any
     */
    record BoundAction(String id,
                       Ids.TenantId tenant,
                       Kind kind,
                       String label,
                       String sendText,
                       String toolId,
                       Map<String, Object> args,
                       Ask ask,
                       String ownerSubject,
                       String recipientPhone,
                       Instant expiresAt,
                       Instant consumedAt,
                       Object result) {

        public boolean expired(Instant now) {
            return expiresAt != null && now.isAfter(expiresAt);
        }

        /** The tap must come from the identity the message was delivered to. */
        public boolean boundTo(String subject, String phone) {
            boolean subjectMatch = ownerSubject != null && ownerSubject.equals(subject);
            boolean phoneMatch = recipientPhone != null && recipientPhone.equals(phone);
            return subjectMatch || phoneMatch;
        }
    }
}
