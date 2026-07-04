package com.yayatechandinnovations.yayaagentic.outreach;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.system.OutreachRecipient;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.outreach.media.MediaTokenSigner;
import com.yayatechandinnovations.yayaagentic.persistence.ChatInboxEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatInboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Store-and-forward drain for chat delivery (§11). The operator's client calls
 * this on open/resume; undelivered rows for their verified identity stream back
 * and are marked delivered. The {@code chat_inbox} row is the source of truth —
 * push (SM-3) only optimizes latency.
 */
@Service
public class ChatInboxService {

    private final ChatInboxRepository repo;
    private final MediaTokenSigner signer;
    private final ObjectMapper json;

    public ChatInboxService(ChatInboxRepository repo, MediaTokenSigner signer, ObjectMapper json) {
        this.repo = repo;
        this.signer = signer;
        this.json = json;
    }

    /**
     * Pending messages for a verified recipient on an entry point, oldest first.
     *
     * @param markDelivered when true, the returned rows are stamped delivered
     *                      (a peek passes false)
     */
    @Transactional
    public List<InboxItem> drain(Ids.TenantId tenant, String entryPointId, String subject,
                                 boolean markDelivered) {
        String recipientKey = new OutreachRecipient.ByPrincipal(subject).key();
        List<ChatInboxEntity> rows = repo
                .findByTenantIdAndEntryPointIdAndRecipientKeyAndDeliveredAtIsNullOrderByCreatedAtAsc(
                        tenant.value(), entryPointId, recipientKey);
        if (markDelivered && !rows.isEmpty()) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            for (ChatInboxEntity r : rows) r.setDeliveredAt(now);
            repo.saveAll(rows);
        }
        return rows.stream().map(r -> toItem(tenant, r)).toList();
    }

    private InboxItem toItem(Ids.TenantId tenant, ChatInboxEntity e) {
        List<Attachment> attachments = new ArrayList<>();
        try {
            List<Map<String, Object>> media = json.readValue(
                    e.getMediaJson() == null ? "[]" : e.getMediaJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> m : media) {
                String assetId = String.valueOf(m.get("assetId"));
                // Mint a fresh short-lived signed URL at drain — never store it (§10 #2).
                attachments.add(new Attachment(
                        assetId,
                        String.valueOf(m.getOrDefault("contentType", "application/octet-stream")),
                        m.get("filename") == null ? null : String.valueOf(m.get("filename")),
                        m.get("sizeBytes") instanceof Number n ? n.longValue() : 0L,
                        "/v1/media/" + signer.mint(tenant, assetId)));
            }
        } catch (Exception ignored) {
            // A malformed media blob never blocks text delivery.
        }
        return new InboxItem(e.getId(), e.getSessionId(), e.getSenderDisplay(),
                e.getSubject(), e.getBody(),
                e.getCreatedAt() == null ? Instant.now() : e.getCreatedAt().toInstant(),
                attachments);
    }

    public record InboxItem(
            long id,
            String sessionId,
            String senderDisplay,
            String subject,
            String body,
            Instant createdAt,
            List<Attachment> attachments
    ) {}

    /** A delivered attachment with its short-lived signed URL. */
    public record Attachment(String assetId, String contentType, String filename,
                             long sizeBytes, String url) {}
}

