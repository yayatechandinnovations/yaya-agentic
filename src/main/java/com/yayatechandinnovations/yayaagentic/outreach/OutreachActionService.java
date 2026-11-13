package com.yayatechandinnovations.yayaagentic.outreach;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.action.BoundActionStore;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachActionEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachActionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registers the action buttons of a proactive message at delivery and resolves
 * taps on them (whatsapp-ops-integration-design.md §6.4). Every button — text
 * or bound tool — gets a 128-bit random id: the capability the WhatsApp payload
 * ({@code act:<id>}) or the chat selection carries. A row is single-use for
 * tools, expires with Twilio's 7-day reply-correlation window, and is bound to
 * the identity the message was delivered to.
 */
@Service
public class OutreachActionService implements BoundActionStore {

    private static final Logger log = LoggerFactory.getLogger(OutreachActionService.class);

    static final Duration TTL = Duration.ofDays(7);
    /** Expired rows linger this long for audit before the sweep drops them. */
    private static final Duration RETENTION = Duration.ofDays(30);
    private static final int MAX_RESULT_CHARS = 16_000;
    private static final int MAX_LABEL_CHARS = 64;

    private final OutreachActionRepository repo;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public OutreachActionService(OutreachActionRepository repo, ObjectMapper json) {
        this.repo = repo;
        this.json = json;
    }

    /**
     * Persist the message's actions for one recipient and return them with
     * their ids, in order.
     */
    @Transactional
    public List<ActionItem> register(Ids.TenantId tenant, Ids.OutreachId outreachId, List<ActionItem> actions,
                                     String ownerSubject, String recipientPhone) {
        if (actions == null || actions.isEmpty()) return List.of();
        OffsetDateTime expires = OffsetDateTime.now(ZoneOffset.UTC).plus(TTL);
        List<ActionItem> out = new ArrayList<>(actions.size());
        for (int i = 0; i < actions.size(); i++) {
            ActionItem a = actions.get(i);
            String id = newId();
            String label = a.label().length() > MAX_LABEL_CHARS ? a.label().substring(0, MAX_LABEL_CHARS) : a.label();
            OutreachActionEntity e = new OutreachActionEntity(id, tenant.value(), outreachId.value(), i,
                    a.bound() ? Kind.TOOL.name() : Kind.SEND.name(), label, a.style(), expires);
            e.setSendText(a.send());
            if (a.bound()) {
                e.setToolId(a.tool().id());
                e.setArgsJson(write(a.tool().args()));
                if (a.ask() != null) e.setAskJson(write(a.ask()));
            }
            e.setOwnerSubject(ownerSubject);
            e.setRecipientPhone(recipientPhone);
            repo.save(e);
            out.add(a.withActionId(id));
        }
        return out;
    }

    @Override
    public Optional<BoundAction> find(Ids.TenantId tenant, String actionId) {
        if (actionId == null || actionId.isBlank()) return Optional.empty();
        return repo.findByTenantIdAndId(tenant.value(), actionId.trim()).map(this::toBound);
    }

    @Override
    @Transactional
    public boolean claim(Ids.TenantId tenant, String actionId, String consumedBy) {
        return repo.claim(tenant.value(), actionId, consumedBy, OffsetDateTime.now(ZoneOffset.UTC)) == 1;
    }

    @Override
    @Transactional
    public void complete(Ids.TenantId tenant, String actionId, Object result) {
        String serialized = write(result == null ? Map.of() : result);
        if (serialized != null && serialized.length() > MAX_RESULT_CHARS) {
            serialized = write(Map.of("truncated", true,
                    "summary", serialized.substring(0, MAX_RESULT_CHARS - 200)));
        }
        repo.complete(tenant.value(), actionId, serialized);
    }

    @Override
    @Transactional
    public void release(Ids.TenantId tenant, String actionId) {
        repo.release(tenant.value(), actionId);
    }

    @Scheduled(cron = "0 17 4 * * *")
    @Transactional
    public void sweepExpired() {
        int n = repo.deleteExpiredBefore(OffsetDateTime.now(ZoneOffset.UTC).minus(RETENTION));
        if (n > 0) log.info("outreach_actions: dropped {} rows expired for over {}", n, RETENTION);
    }

    private BoundAction toBound(OutreachActionEntity e) {
        Map<String, Object> args = read(e.getArgsJson(), new TypeReference<Map<String, Object>>() {}, Map.of());
        Ask ask = null;
        Map<String, Object> rawAsk = read(e.getAskJson(), new TypeReference<Map<String, Object>>() {}, null);
        if (rawAsk != null && rawAsk.get("arg") != null) {
            List<String> suggestions = new ArrayList<>();
            if (rawAsk.get("suggestions") instanceof List<?> l) {
                for (Object o : l) if (o != null) suggestions.add(String.valueOf(o));
            }
            ask = new Ask(String.valueOf(rawAsk.get("arg")),
                    rawAsk.get("prompt") == null ? null : String.valueOf(rawAsk.get("prompt")), suggestions);
        }
        Object result = e.getResultJson() == null ? null
                : read(e.getResultJson(), new TypeReference<Object>() {}, null);
        return new BoundAction(e.getId(), new Ids.TenantId(e.getTenantId()), Kind.valueOf(e.getKind()),
                e.getLabel(), e.getSendText(), e.getToolId(), args, ask,
                e.getOwnerSubject(), e.getRecipientPhone(),
                e.getExpiresAt() == null ? null : e.getExpiresAt().toInstant(),
                e.getConsumedAt() == null ? null : e.getConsumedAt().toInstant(),
                result);
    }

    private String newId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize outreach action", e);
        }
    }

    private <T> T read(String raw, TypeReference<T> type, T fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            T v = json.readValue(raw, type);
            return v == null ? fallback : v;
        } catch (Exception e) {
            return fallback;
        }
    }
}
