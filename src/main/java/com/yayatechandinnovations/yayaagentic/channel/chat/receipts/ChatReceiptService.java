package com.yayatechandinnovations.yayaagentic.channel.chat.receipts;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.persistence.ChatReceiptEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatReceiptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The receipt wallet ({@code chat-experience-design.md} §7.5): captures
 * {@code receipt} UiHint frames flowing through a verified session so tickets
 * outlive the chat, and serves the account's wallet page. The template is
 * snapshotted at write — instances never retro-edit.
 */
@Service
public class ChatReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ChatReceiptService.class);
    private static final int PAGE_SIZE = 20;

    private final ChatReceiptRepository receipts;
    private final ReceiptTemplateService templates;

    public ChatReceiptService(ChatReceiptRepository receipts, ReceiptTemplateService templates) {
        this.receipts = receipts;
        this.templates = templates;
    }

    /**
     * Peek at a streaming turn event; persist {@code receipt} hints for a
     * verified (account-linked) session. Anonymous sessions still RENDER
     * receipts — they just aren't collected. Never throws: a wallet write
     * failure must not break the live stream.
     */
    public void maybePersist(TurnEvent event, Ids.TenantId tenant, String sessionId,
                             String handle, UUID accountId) {
        if (accountId == null) return;
        if (!(event instanceof TurnEvent.UiHint hint) || !"receipt".equals(hint.kind())) return;
        // A draft is a ticket being built (chat-experience-design.md §7.8) —
        // rendered live but never collected; only the issued document persists.
        Map<String, Object> payload = hint.payload();
        if (payload != null && Boolean.TRUE.equals(payload.get("draft"))) return;
        try {
            persist(tenant, sessionId, handle, accountId, payload);
        } catch (Exception e) {
            log.warn("could not persist receipt for session {}: {}", sessionId, e.toString());
        }
    }

    @Transactional
    void persist(Ids.TenantId tenant, String sessionId, String handle, UUID accountId,
                 Map<String, Object> payload) {
        if (payload == null) return;
        Object data = payload.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) return;
        String templateId = payload.get("templateId") == null
                ? null : String.valueOf(payload.get("templateId"));

        // Snapshot the layout at issuance (resolved decision §12.3 in the doc):
        // resolve the template now; fall back to a minimal shell so the wallet
        // row is still renderable when the template id is unknown.
        Map<String, Object> snapshot = templateId == null
                ? null
                : templates.find(tenant.value(), templateId)
                        .map(ReceiptTemplateService::payloadOf).orElse(null);
        if (snapshot == null) {
            snapshot = Map.of("id", templateId == null ? "unknown" : templateId,
                    "name", "Receipt",
                    "paper", Map.of("cols", 32, "profile", "58mm"),
                    "elements", List.of());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> dataCopy = new HashMap<>((Map<String, Object>) dataMap);
        String id = "rcpt_" + UUID.randomUUID().toString().replace("-", "");
        receipts.save(new ChatReceiptEntity(id, tenant.value(), accountId, sessionId,
                handle, templateId, snapshot, dataCopy));
    }

    /* -------------------------------- wallet -------------------------------- */

    public record WalletPage(List<ChatReceiptEntity> receipts, String nextCursor) {}

    /** Keyset-paged wallet, newest first. Cursor = {@code <createdAt>|<id>}. */
    @Transactional(readOnly = true)
    public WalletPage wallet(UUID accountId, String cursor) {
        List<ChatReceiptEntity> page;
        var pageable = PageRequest.of(0, PAGE_SIZE + 1);
        if (cursor == null || cursor.isBlank()) {
            page = receipts.findByAccountIdOrderByCreatedAtDescIdDesc(accountId, pageable);
        } else {
            int sep = cursor.lastIndexOf('|');
            if (sep <= 0) return new WalletPage(List.of(), null);
            try {
                OffsetDateTime at = OffsetDateTime.parse(cursor.substring(0, sep));
                page = receipts.pageAfter(accountId, at, cursor.substring(sep + 1), pageable);
            } catch (RuntimeException e) {
                return new WalletPage(List.of(), null);
            }
        }
        String next = null;
        if (page.size() > PAGE_SIZE) {
            page = page.subList(0, PAGE_SIZE);
            ChatReceiptEntity last = page.get(page.size() - 1);
            next = last.getCreatedAt() + "|" + last.getId();
        }
        return new WalletPage(page, next);
    }

    /** GDPR erase with the account ({@code chat-account-self-service} G4). */
    @Transactional
    public void deleteForAccount(UUID accountId) {
        receipts.deleteByAccountId(accountId);
    }
}
