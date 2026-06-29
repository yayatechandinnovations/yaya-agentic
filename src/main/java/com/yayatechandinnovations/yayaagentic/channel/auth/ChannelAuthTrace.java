package com.yayatechandinnovations.yayaagentic.channel.auth;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory, per-session record of the identity-resolution stages an inbound
 * message went through, for the playground auth-flow inspector: which binding
 * matched, what the JWT-verification decision was, and whether a session token
 * was stored. Keyed by the engine {@code SessionId}.
 *
 * <p>The durable resolver-call detail (status, http_status, latency) already
 * lives in {@code auth_resolver_calls}; this captures only the three stages
 * the worker computes that aren't otherwise persisted. Single-instance +
 * bounded, matching {@code PlaygroundOutboundSink} / {@code
 * PlaygroundSessionRegistry}: this is operator playground tooling, not a
 * durable audit surface.
 *
 * <p>See {@code playground-auth-flow-inspector-design.md} §6.2.
 */
@Component
public class ChannelAuthTrace {

    private static final int MAX_ENTRIES = 10_000;

    /** Whether a resolver-minted JWT ended up on the session row. */
    public enum TokenState { STORED, ABSENT, CIPHER_DISABLED }

    /** Immutable snapshot the inspector reads. Any stage may be null if the
     *  worker hadn't reached it (e.g. message rejected before verification). */
    public record Snapshot(String bindingId,
                           String profileId,
                           Long resolverId,
                           String onUnresolved,
                           String verificationDecision,
                           String verificationDetail,
                           String tokenState) {}

    private static final class Mutable {
        String bindingId, profileId, onUnresolved;
        Long resolverId;
        String verificationDecision, verificationDetail;
        String tokenState;

        Snapshot snapshot() {
            return new Snapshot(bindingId, profileId, resolverId, onUnresolved,
                    verificationDecision, verificationDetail, tokenState);
        }
    }

    private final Map<String, Mutable> bySession = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Mutable> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    public void recordBinding(Ids.SessionId session, String bindingId, String profileId,
                              Long resolverId, String onUnresolved) {
        if (session == null) return;
        Mutable m = entry(session);
        synchronized (m) {
            m.bindingId = bindingId;
            m.profileId = profileId;
            m.resolverId = resolverId;
            m.onUnresolved = onUnresolved;
        }
    }

    public void recordVerification(Ids.SessionId session, String decision, String detail) {
        if (session == null) return;
        Mutable m = entry(session);
        synchronized (m) {
            m.verificationDecision = decision;
            m.verificationDetail = detail;
        }
    }

    public void recordSessionToken(Ids.SessionId session, TokenState state) {
        if (session == null) return;
        Mutable m = entry(session);
        synchronized (m) {
            m.tokenState = state == null ? null : state.name();
        }
    }

    public Optional<Snapshot> get(Ids.SessionId session) {
        if (session == null) return Optional.empty();
        Mutable m = bySession.get(session.value());
        if (m == null) return Optional.empty();
        synchronized (m) {
            return Optional.of(m.snapshot());
        }
    }

    public void wipe(Ids.SessionId session) {
        if (session == null) return;
        bySession.remove(session.value());
    }

    private Mutable entry(Ids.SessionId session) {
        return bySession.computeIfAbsent(session.value(), k -> new Mutable());
    }
}
