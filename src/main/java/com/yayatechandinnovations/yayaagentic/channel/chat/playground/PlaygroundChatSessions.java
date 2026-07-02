package com.yayatechandinnovations.yayaagentic.channel.chat.playground;

import com.yayatechandinnovations.yayaagentic.channel.chat.ChatAuthService;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointId;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory map from a chat-playground engine session id to the operator-chosen
 * simulated identity, so each subsequent message/action re-derives the exact
 * same {@link ChatAuthService.Caller} the session started with — the playground
 * analogue of the public door's {@code ChatSessionRegistry}, but holding the
 * synthesised identity (which the Redis registry deliberately never stores).
 *
 * <p>Bounded + single-instance, matching {@code PlaygroundSessionRegistry} /
 * {@code PlaygroundOutboundSink}: this is operator playground tooling, not a
 * durable surface.
 */
@Component
public class PlaygroundChatSessions {

    private static final int MAX_ENTRIES = 10_000;

    /**
     * @param authenticated whether the simulated identity should be treated as an
     *                      already-authenticated session on re-resolve (a pasted
     *                      bearer); phone/anonymous sessions are not
     */
    public record Session(Ids.TenantId tenant,
                          ChatEntryPointId entryPoint,
                          String deviceId,
                          ChatAuthService.Caller caller,
                          boolean authenticated) {}

    private final Map<String, Session> bySession = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Session> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    public void put(Ids.SessionId session, Session s) {
        if (session == null) return;
        bySession.put(session.value(), s);
    }

    public Optional<Session> get(Ids.SessionId session) {
        if (session == null) return Optional.empty();
        return Optional.ofNullable(bySession.get(session.value()));
    }

    public void remove(Ids.SessionId session) {
        if (session == null) return;
        bySession.remove(session.value());
    }
}
