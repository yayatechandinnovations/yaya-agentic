package com.yayatechandinnovations.yayaagentic.channel.sms;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.engine.confirm.ConfirmPrompts;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutboundMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

/**
 * Buffers the engine's {@code TurnEvent} flux into one finalized SMS body and
 * dispatches it through the {@link SmsTransport} pipe ({@code CompletionPolicy.Complete},
 * design §5.1). SMS has no streaming, templates, interactive buttons, or 24h
 * window — so this is far leaner than the WhatsApp {@code OutboundDispatcher}.
 *
 * <p>Quick-replies degrade to numbered text lines (SMS has no buttons). The
 * billed segment count is stamped on {@code outbound_messages} for billing /
 * the long-reply eval; we never truncate.
 */
@Component
public class SmsOutboundDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SmsOutboundDispatcher.class);

    private final SmsSenderService senders;
    private final SmsTransport transport;
    private final OutboundMessageRepository outbound;

    public SmsOutboundDispatcher(SmsSenderService senders,
                                 SmsTransport transport,
                                 OutboundMessageRepository outbound) {
        this.senders = senders;
        this.transport = transport;
        this.outbound = outbound;
    }

    /** Dispatch the engine's reply for a turn. Blocks draining the flux — the
     *  caller is the async inbound worker, so blocking here is intended. */
    public void dispatchTurn(Ids.TenantId tenant, SmsSenderId senderId, String toPhone,
                             Ids.SessionId session, Flux<TurnEvent> events) {
        List<TurnEvent> all = events.collectList().block();
        StringBuilder body = new StringBuilder();
        boolean confirmFrame = false;
        String planReplyHint = null;
        for (TurnEvent e : all == null ? List.<TurnEvent>of() : all) {
            if (e instanceof TurnEvent.Token t) body.append(t.text());
            if (e instanceof TurnEvent.UiHint h && "confirm".equals(h.kind())) confirmFrame = true;
            if (e instanceof TurnEvent.UiHint h && "plan".equals(h.kind())) {
                planReplyHint = ConfirmPrompts.planReplyHintFor(h.payload());
            }
        }
        // SMS has no card or buttons: the confirm frame's question — or a
        // plan's list of steps — is the whole UX, so the reply instruction is
        // added here, once (whatsapp-ops §5, R2). A plan asks for words that
        // cannot be taken for one of its step numbers.
        if (planReplyHint != null) {
            if (!body.toString().endsWith(planReplyHint)) {
                body.append(body.length() == 0 ? "" : " ").append(planReplyHint);
            }
        } else if (confirmFrame) {
            body.append(body.length() == 0 ? "" : " ").append(CONFIRM_REPLY_HINT);
        }
        send(tenant, senderId, toPhone, session, body.toString());
    }

    static final String CONFIRM_REPLY_HINT = "Responde SÍ o NO.";

    /** Dispatch the session's greeting + any quick-replies as a single SMS. */
    public void dispatchGreeting(Ids.TenantId tenant, SmsSenderId senderId, String toPhone,
                                 Ids.SessionId session, String greeting, List<String> quickReplies) {
        StringBuilder body = new StringBuilder(greeting == null ? "" : greeting);
        if (quickReplies != null && !quickReplies.isEmpty()) {
            body.append("\n");
            for (int i = 0; i < quickReplies.size(); i++) {
                body.append("\n").append(i + 1).append(") ").append(quickReplies.get(i));
            }
        }
        send(tenant, senderId, toPhone, session, body.toString());
    }

    private void send(Ids.TenantId tenant, SmsSenderId senderId, String toPhone,
                      Ids.SessionId session, String body) {
        if (body == null || body.isBlank()) return;

        SmsSender sender = senders.requireForTenant(tenant, senderId);
        int segments = SmsSegmenter.segmentCount(body);
        String messageId = "smsout_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        OutboundMessageEntity row = new OutboundMessageEntity(
                messageId, tenant.value(), senderId.value(), toPhone,
                session == null ? null : session.value(), body);
        row.setSegmentCount(segments);

        try {
            transport.send(new SmsMessage(toPhone, sender.phoneE164(), body, messageId));
            row.setStatus("SENT");
            outbound.save(row);
            log.debug("SMS outbound sent sender={} to={} segments={}", senderId.value(), toPhone, segments);
        } catch (SmsSendException e) {
            row.setStatus("FAILED");
            row.setFailureMessage(e.getMessage());
            outbound.save(row);
            log.warn("SMS outbound failed sender={} to={}: {}", senderId.value(), toPhone, e.getMessage());
        }
    }
}
