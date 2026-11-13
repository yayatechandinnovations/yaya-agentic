package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachDeliveryEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Context carryover for WhatsApp (GAP-W6 of whatsapp-ops-integration-design.md
 * §6.6). A proactive message often goes out while no conversation is open, and
 * the user answers hours later by typing ("apruébala"). Before such a reply
 * reaches the engine, every outreach this phone received on this number in
 * the last 7 days that the live conversation has not seen yet is appended as
 * a SYSTEM turn — the message the user quoted, if any, last. Button taps don't
 * need this (the action row carries everything); typed replies do.
 */
@Component
public class OutreachContextSeeder {

    private static final Logger log = LoggerFactory.getLogger(OutreachContextSeeder.class);

    /** Matches Twilio's 7-day reply-correlation window and the action TTL. */
    private static final Duration LOOKBACK = Duration.ofDays(7);

    private final OutreachDeliveryRepository deliveries;
    private final ConversationEngine engine;

    public OutreachContextSeeder(OutreachDeliveryRepository deliveries, ConversationEngine engine) {
        this.deliveries = deliveries;
        this.engine = engine;
    }

    public void seed(Ids.TenantId tenant, SenderId sender, String phone, Ids.SessionId sessionId,
                     String repliedToMessageSid) {
        if (sessionId == null || phone == null) return;
        try {
            List<OutreachDeliveryEntity> rows = deliveries
                    .findByTenantIdAndWhatsappSenderIdAndEndUserPhoneAndChannelAndCreatedAtAfterOrderByCreatedAtAsc(
                            tenant.value(), sender.value(), phone, "WHATSAPP",
                            OffsetDateTime.now(ZoneOffset.UTC).minus(LOOKBACK));
            List<OutreachDeliveryEntity> unseen = new ArrayList<>();
            OutreachDeliveryEntity quoted = null;
            for (OutreachDeliveryEntity d : rows) {
                if (sessionId.value().equals(d.getSeededSessionId())) continue;
                if (d.getBody() == null || d.getBody().isBlank()) continue;
                if (repliedToMessageSid != null && repliedToMessageSid.equals(d.getProviderMessageSid())) {
                    quoted = d;
                } else {
                    unseen.add(d);
                }
            }
            if (quoted != null) unseen.add(quoted);
            for (OutreachDeliveryEntity d : unseen) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("outreachId", d.getOutreachId());
                meta.put("channel", "whatsapp");
                if (d.getTemplateName() != null) meta.put("template", d.getTemplateName());
                if (d == quoted) meta.put("quotedByUser", true);
                if (engine.appendSystemTurn(sessionId, d.getBody(), meta)) {
                    d.setSeededSessionId(sessionId.value());
                    deliveries.save(d);
                }
            }
        } catch (RuntimeException e) {
            // Context is a nicety; the reply itself must still go through.
            log.warn("outreach context seed failed for session {}: {}", sessionId.value(), e.getMessage());
        }
    }
}
