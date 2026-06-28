package com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bridges the engine's internal session id (a fresh {@code UUID} minted by
 * {@code engine.start}, unknowable to a client) to the client-chosen session
 * id the Flutter playground polls {@code /outbound/{sessionId}} with.
 *
 * <p>Without this, the {@link PlaygroundOutboundSink} is keyed by the engine
 * UUID while the UI forever polls its own {@code wa-<timestamp>} id, so the
 * bot's reply lands in a bucket the client never reads. The {@link
 * com.yayatechandinnovations.yayaagentic.channel.whatsapp.InboundDispatchWorker}
 * registers the mapping after session resolution; {@link
 * com.yayatechandinnovations.yayaagentic.channel.whatsapp.OutboundDispatcher}
 * consults it when keying the sink for the playground sender.
 *
 * <p>In-memory + single-instance, matching {@link PlaygroundOutboundSink}'s
 * own deployment assumption. Bounded so a long-running instance can't leak
 * the map; oldest mappings drop.
 */
@Component
public class PlaygroundSessionRegistry {

    private static final int MAX_ENTRIES = 10_000;

    private final Map<String, String> engineToClient = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    public void register(Ids.SessionId engineSession, String clientSessionId) {
        if (clientSessionId == null || clientSessionId.isBlank()) return;
        engineToClient.put(engineSession.value(), clientSessionId);
    }

    public Optional<String> clientFor(Ids.SessionId engineSession) {
        return Optional.ofNullable(engineToClient.get(engineSession.value()));
    }
}
