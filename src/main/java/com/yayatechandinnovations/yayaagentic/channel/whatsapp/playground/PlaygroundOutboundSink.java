package com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground;

import com.yayatechandinnovations.yayaagentic.channel.InteractivePayload;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory FIFO buffer of outbound messages destined for the playground.
 * Keyed by session id — the playground UI polls per session. The sink is
 * bounded so a runaway loop doesn't OOM the JVM; oldest messages drop.
 *
 * <p>Single-instance only — multi-instance deployments would need a shared
 * broker (Redis pub/sub or similar). The playground is operator-tier
 * tooling; if a deployment needs multi-instance playground state, it's
 * already big enough to have Redis at hand.
 */
@Component
public class PlaygroundOutboundSink {

    /** Per-session ring limit so a buggy profile loop can't OOM the buffer. */
    private static final int MAX_MESSAGES_PER_SESSION = 1000;

    private final Map<String, List<Message>> bySession = new LinkedHashMap<>();

    public synchronized Message append(Ids.SessionId session, String body,
                                       InteractivePayload payload) {
        List<Message> queue = bySession.computeIfAbsent(session.value(),
                k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (queue) {
            if (queue.size() >= MAX_MESSAGES_PER_SESSION) {
                queue.remove(0);
            }
            Message message = new Message(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    body,
                    descriptorFor(payload));
            queue.add(message);
            return message;
        }
    }

    /** Drain new messages since the given sequence number. Returns all
     *  buffered messages when {@code afterId} is null. */
    public synchronized List<Message> drain(Ids.SessionId session, String afterId) {
        List<Message> queue = bySession.get(session.value());
        if (queue == null) return List.of();
        synchronized (queue) {
            if (afterId == null || afterId.isBlank()) return List.copyOf(queue);
            int start = 0;
            for (int i = 0; i < queue.size(); i++) {
                if (afterId.equals(queue.get(i).id())) {
                    start = i + 1;
                    break;
                }
            }
            return start >= queue.size() ? List.of() : List.copyOf(queue.subList(start, queue.size()));
        }
    }

    public synchronized void clearSession(Ids.SessionId session) {
        bySession.remove(session.value());
    }

    /** Visible-for-test introspection only. */
    public synchronized int size(Ids.SessionId session) {
        List<Message> q = bySession.get(session.value());
        return q == null ? 0 : q.size();
    }

    private static InteractiveDescriptor descriptorFor(InteractivePayload payload) {
        return switch (payload) {
            case null -> null;
            case InteractivePayload.PlainText pt -> null;
            case InteractivePayload.Buttons b -> new InteractiveDescriptor(
                    "buttons",
                    b.buttons().stream().map(btn ->
                            Map.<String, Object>of("id", btn.id(), "label", btn.title())).toList(),
                    null,
                    b.footer());
            case InteractivePayload.ListMessage lm -> {
                List<Map<String, Object>> rows = new ArrayList<>();
                lm.sections().forEach(s -> s.rows().forEach(r -> rows.add(
                        Map.of("id", r.id(), "label", r.title(),
                                "description", r.description() == null ? "" : r.description()))));
                yield new InteractiveDescriptor("list", rows, lm.header(), lm.footer());
            }
        };
    }

    public record Message(String id, Instant sentAt, String body,
                          InteractiveDescriptor interactive) {}

    public record InteractiveDescriptor(String kind, List<Map<String, Object>> options,
                                        String header, String footer) {}
}
