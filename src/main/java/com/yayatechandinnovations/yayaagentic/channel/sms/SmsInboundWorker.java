package com.yayatechandinnovations.yayaagentic.channel.sms;

import com.yayatechandinnovations.yayaagentic.core.TurnChannel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.ChannelEndpointRef;
import com.yayatechandinnovations.yayaagentic.channel.SecretInboundRedaction;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBinding;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBindingService;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.ChannelSessionService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.StartSessionResult;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Async processor for inbound SMS — the lean SMS twin of
 * {@code channel.whatsapp.InboundDispatchWorker}. Resolves the binding, applies
 * session continuity, runs the engine, and dispatches the reply. Deliberately
 * omits the WhatsApp worker's media transcription, templates, step-up OTP
 * interception, and auth-resolver path (FAQ-mode bindings only in v1) — those
 * are follow-ons, not part of the core loop.
 *
 * <p>The {@link SmsPhoneAuthenticator} mints the {@code sms:+E164} principal
 * from the channel headers we stamp into {@link AuthContext}; the engine runs
 * the authenticator chain on {@code start}.
 */
@Component
public class SmsInboundWorker {

    private static final Logger log = LoggerFactory.getLogger(SmsInboundWorker.class);
    private static final TypeReference<Map<String, Object>> RAW = new TypeReference<>() {};
    private static final Set<String> STOP_KEYWORDS = Set.of("stop", "stopall", "unsubscribe", "cancel", "end", "quit");
    private static final Set<String> HELP_KEYWORDS = Set.of("help", "info");

    private final ThreadPoolTaskExecutor executor;
    private final InboundMessageRepository inbound;
    private final SmsSenderService senders;
    private final ChannelBindingService bindings;
    private final ChannelSessionService sessions;
    private final ConversationEngine engine;
    private final SmsOutboundDispatcher dispatcher;
    private final ObjectMapper json;

    public SmsInboundWorker(@Qualifier("channelDispatchExecutor") ThreadPoolTaskExecutor executor,
                            InboundMessageRepository inbound,
                            SmsSenderService senders,
                            ChannelBindingService bindings,
                            ChannelSessionService sessions,
                            ConversationEngine engine,
                            SmsOutboundDispatcher dispatcher,
                            ObjectMapper json) {
        this.executor = executor;
        this.inbound = inbound;
        this.senders = senders;
        this.bindings = bindings;
        this.sessions = sessions;
        this.engine = engine;
        this.dispatcher = dispatcher;
        this.json = json;
    }

    public void schedule(String messageSid) {
        executor.execute(() -> {
            try {
                process(messageSid);
            } catch (RuntimeException e) {
                log.warn("SMS inbound processing failed for {}: {}", messageSid, e.getMessage());
                markFailed(messageSid, e.getMessage());
            }
        });
    }

    void process(String messageSid) {
        InboundMessageEntity row = inbound.findById(messageSid).orElse(null);
        if (row == null || row.getProcessedAt() != null) return;

        Ids.TenantId tenant = new Ids.TenantId(row.getTenantId());
        SmsSenderId senderId = new SmsSenderId(row.getSenderId());
        SmsSender sender = senders.requireForTenant(tenant, senderId);
        if (!sender.status().acceptsInbound()) {
            markProcessed(row);
            return;
        }

        String phone = row.getEndUserPhone();
        String body = stringField(row.getRawPayloadJson(), "Body");

        // basic carrier-mandated keyword handling (real opt-out persistence is a
        // follow-on that reuses OptOutService).
        String norm = body == null ? "" : body.trim().toLowerCase();
        if (STOP_KEYWORDS.contains(norm)) {
            dispatcher.dispatchGreeting(tenant, senderId, phone, null,
                    "You have been unsubscribed and will no longer receive messages. Reply HELP for help.", null);
            markProcessed(row);
            return;
        }
        if (HELP_KEYWORDS.contains(norm)) {
            dispatcher.dispatchGreeting(tenant, senderId, phone, null,
                    "Reply STOP to unsubscribe. For assistance, contact support.", null);
            markProcessed(row);
            return;
        }

        // §15 — a code prompt may be waiting in this customer's conversation.
        // If this message is, or may hold, the code, the stored row gives it
        // up now: before any branch below can return or fail.
        var waSenderId = new com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId(senderId.value());
        final boolean secretInbound = redactIfCode(row, sessions.findOpen(tenant, waSenderId, phone), body);

        ChannelEndpointRef endpoint = new ChannelEndpointRef.SmsSenderRef(senderId);
        Optional<ChannelBinding> bindingOpt = bindings.findLowestPriorityActiveForEndpoint(tenant, endpoint);
        if (bindingOpt.isEmpty()) {
            // no route — drop without an LLM round-trip or a user-facing reply
            markProcessed(row);
            return;
        }
        ChannelBinding binding = bindingOpt.get();
        SessionPolicy policy = binding.sessionPolicy();

        // session continuity — channel_sessions is channel-agnostic since V45.
        ChannelSessionService.Outcome cont = sessions.openOrReuse(tenant, waSenderId, phone, body, policy);

        Ids.SessionId sessionId;
        boolean fresh;
        boolean consumedKeyword;
        if (cont instanceof ChannelSessionService.Outcome.Reuse reuse) {
            sessionId = reuse.existing();
            fresh = false;
            consumedKeyword = reuse.consumedKeyword();
        } else if (cont instanceof ChannelSessionService.Outcome.Reopen reopen) {
            sessionId = null;
            fresh = true;
            consumedKeyword = reopen.consumedKeyword();
        } else {
            sessionId = null;
            fresh = true;
            consumedKeyword = ((ChannelSessionService.Outcome.Open) cont).consumedKeyword();
        }
        // §15: the engine reads a code out of the text only in the conversation
        // whose prompt it answers. If this message opens a new one (the other
        // idled out), the text goes on as the marker.
        String userText = consumedKeyword ? "" : secretInbound && fresh ? SecretInboundRedaction.MARKER : body;

        StartConversationRequest startReq = new StartConversationRequest(
                tenant, Optional.of(binding.profile()), new com.yayatechandinnovations.yayaagentic
                .channel.ChannelKind.Sms().wireName(), Map.of());
        AuthContext auth = new AuthContext(tenant, channelHeaders(phone, senderId, sender, messageSid), Optional.empty());

        if (fresh) {
            StartSessionResult result = engine.start(startReq, auth.onChannel(TurnChannel.SMS));
            sessionId = result.session().id();
            sessions.register(tenant, waSenderId, phone, sessionId);
            dispatcher.dispatchGreeting(tenant, senderId, phone, sessionId,
                    result.greeting(), result.quickReplies());
            if (!userText.isBlank()) {
                dispatcher.dispatchTurn(tenant, senderId, phone, sessionId,
                        com.yayatechandinnovations.yayaagentic.usage.QuotaRefusals.spoken(engine.send(sessionId, new UserMessage(userText, Map.of()), auth.onChannel(TurnChannel.SMS))));
            }
        } else if (!userText.isBlank()) {
            dispatcher.dispatchTurn(tenant, senderId, phone, sessionId,
                    com.yayatechandinnovations.yayaagentic.usage.QuotaRefusals.spoken(engine.send(sessionId, new UserMessage(userText, Map.of()), auth.onChannel(TurnChannel.SMS))));
        }

        markProcessed(row);
    }

    private Map<String, String> channelHeaders(String phone, SmsSenderId senderId,
                                                SmsSender sender, String messageSid) {
        Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put(SmsPhoneAuthenticator.HEADER_END_USER, phone);
        h.put(SmsPhoneAuthenticator.HEADER_SENDER_ID, senderId.value());
        if (sender.defaultLocale() != null) h.put(SmsPhoneAuthenticator.HEADER_LOCALE, sender.defaultLocale());
        h.put(SmsPhoneAuthenticator.HEADER_MESSAGE_SID, messageSid);
        return h;
    }

    private String stringField(String rawJson, String key) {
        try {
            Object v = json.readValue(rawJson, RAW).get(key);
            return v == null ? "" : v.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void markProcessed(InboundMessageEntity row) {
        row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
        inbound.save(row);
    }

    /**
     * §15 — takes the text out of {@code row} (and saves it) when a code
     * prompt is waiting in {@code session} and {@code text} is, or may hold,
     * the code.
     */
    private boolean redactIfCode(InboundMessageEntity row, Optional<Ids.SessionId> session, String text) {
        if (!SecretInboundRedaction.holdsCode(engine, session, text, TurnChannel.SMS)) return false;
        SecretInboundRedaction.apply(row, json);
        inbound.save(row);
        return true;
    }

    private void markFailed(String messageSid, String error) {
        inbound.findById(messageSid).ifPresent(row -> {
            // A row that failed before the check in the worker still gives up a code.
            try {
                redactIfCode(row, sessions.findOpen(new Ids.TenantId(row.getTenantId()),
                        new com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId(row.getSenderId()),
                        row.getEndUserPhone()), stringField(row.getRawPayloadJson(), "Body"));
            } catch (RuntimeException e) {
                log.warn("SMS inbound: could not check failed row {} for a code: {}",
                        messageSid, e.getClass().getSimpleName());
            }
            row.setProcessedAt(OffsetDateTime.now(ZoneOffset.UTC));
            row.setProcessingError(error);
            inbound.save(row);
        });
    }
}
