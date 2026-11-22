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
 * <p>It also remembers, per tenant, the Playground conversation each simulated
 * phone last wrote from, so a proactive message for that phone on the
 * Playground number ({@code WhatsAppOutreachLeg}) lands in the conversation
 * the operator is looking at.
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

    // Reverse direction: the inspector endpoint receives the client-chosen id
    // the UI polls with and needs the engine UUID to read engine state.
    private final Map<String, String> clientToEngine = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    private final Map<String, String> phoneToClient = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    public void register(Ids.SessionId engineSession, String clientSessionId) {
        if (clientSessionId == null || clientSessionId.isBlank()) return;
        engineToClient.put(engineSession.value(), clientSessionId);
        clientToEngine.put(clientSessionId, engineSession.value());
    }

    public Optional<String> clientFor(Ids.SessionId engineSession) {
        return Optional.ofNullable(engineToClient.get(engineSession.value()));
    }

    public Optional<Ids.SessionId> engineFor(String clientSessionId) {
        if (clientSessionId == null || clientSessionId.isBlank()) return Optional.empty();
        return Optional.ofNullable(clientToEngine.get(clientSessionId)).map(Ids.SessionId::new);
    }

    /** The Playground conversation {@code phoneE164} last wrote from in {@code tenant}. */
    public void rememberPhone(Ids.TenantId tenant, String phoneE164, String clientSessionId) {
        if (tenant == null || clientSessionId == null || clientSessionId.isBlank()) return;
        String key = phoneKey(tenant, phoneE164);
        if (key != null) phoneToClient.put(key, clientSessionId);
    }

    public Optional<String> clientForPhone(Ids.TenantId tenant, String phoneE164) {
        if (tenant == null) return Optional.empty();
        String key = phoneKey(tenant, phoneE164);
        return key == null ? Optional.empty() : Optional.ofNullable(phoneToClient.get(key));
    }

    private static String phoneKey(Ids.TenantId tenant, String phone) {
        if (phone == null) return null;
        String p = phone.trim();
        if (p.startsWith("whatsapp:")) p = p.substring("whatsapp:".length());
        p = p.replaceAll("[\\s()-]", "");
        return p.isEmpty() ? null : tenant.value() + "|" + p;
    }
}
