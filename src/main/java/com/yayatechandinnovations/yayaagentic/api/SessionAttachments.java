package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.SessionDtos;
import com.yayatechandinnovations.yayaagentic.channel.chat.media.ChatAttachmentService;
import com.yayatechandinnovations.yayaagentic.channel.chat.media.ChatMessageComposer;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Turn;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import com.yayatechandinnovations.yayaagentic.usage.UsageMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Attachments on the sessions API (assistant-channels design §3.2, tracker
 * contract C), built on the chat's {@link ChatAttachmentService} and
 * {@link ChatMessageComposer}: the same table, blob store, caps and model
 * composition, with admission by the session's owner instead of a chat
 * account. Kept apart from {@link SessionController} so that controller's
 * hunks stay small.
 */
@Component
public class SessionAttachments {

    private static final Logger LOG = LoggerFactory.getLogger(SessionAttachments.class);

    /** The user turn's recorded attachment metadata (what the engine stamps from {@code chat.attachments}). */
    static final String TURN_META_KEY = "attachments";

    private final ChatAttachmentService service;
    private final UsageMeter usage;

    public SessionAttachments(ChatAttachmentService service, UsageMeter usage) {
        this.service = service;
        this.usage = usage;
    }

    public ChatAttachmentService service() {
        return service;
    }

    /**
     * The engine-facing message, the ids it carries (marked referenced when
     * the turn ends) and how many pictures the model will see (metered when
     * the turn ends — a turn refused before the model ran shows no image).
     */
    public record Composed(UserMessage message, List<String> ids, int images) {}

    /**
     * Resolves {@code ids} (this session's, unreferenced, ≤ 4), composes the
     * message (transcripts as speech, PDF text and codes as delimited data,
     * ≤ 2 images as vision input) and keeps the caller's own attachment map
     * entries (selection, client context).
     */
    public Composed compose(Ids.TenantId tenant, String sessionId, String profileId, String text,
                            Map<String, Object> extra, List<String> ids) {
        List<ChatAttachmentService.ChatAttachment> atts;
        try {
            atts = service.resolveForSend(tenant, sessionId, ids);
        } catch (ChatAttachmentService.RejectedException e) {
            throw toApi(e);
        }
        UserMessage composed = ChatMessageComposer.compose(text, atts,
                aid -> service.readBytes(tenant, aid).orElse(null));
        Map<String, Object> merged = new LinkedHashMap<>(composed.attachments());
        if (extra != null) merged.putAll(extra);
        int images = composed.attachments().get(ChatMessageComposer.IMAGES_KEY) instanceof List<?> l ? l.size() : 0;
        return new Composed(new UserMessage(composed.text(), merged),
                atts.stream().map(ChatAttachmentService.ChatAttachment::id).toList(), images);
    }

    /**
     * Single-use: once the turn ends the attachments belong to it, under the
     * engine's turn id (the id the {@code End} event names, which the
     * assistant's first recorded step carries). An errored send leaves them
     * unreferenced for the orphan sweep.
     */
    /**
     * On {@code End}: the attachments are marked referenced and the pictures
     * the model saw are metered as {@code images_in}. A turn that errors or is
     * refused (quota, 409) ends without the event and counts nothing.
     */
    public Flux<TurnEvent> markOnEnd(Flux<TurnEvent> events, Composed composed, Ids.TenantId tenant,
                                     String profileId) {
        List<String> ids = composed == null || composed.ids() == null ? List.of() : composed.ids();
        int images = composed == null ? 0 : composed.images();
        if (ids.isEmpty() && images <= 0) return events;
        return events.doOnNext(evt -> {
            if (!(evt instanceof TurnEvent.End end)) return;
            if (!ids.isEmpty()) {
                try {
                    service.markReferenced(ids, end.turnId().value());
                } catch (RuntimeException e) {
                    LOG.warn("could not mark attachments {} referenced: {}", ids, e.toString());
                }
            }
            if (images > 0 && tenant != null) {
                try {
                    usage.recordImagesIn(tenant.value(), profileId, images);
                } catch (RuntimeException e) {
                    LOG.debug("images_in not recorded: {}", e.toString());
                }
            }
        });
    }

    /** Every attachment of a session by id — read once per transcript render. */
    public Map<String, ChatAttachmentService.ChatAttachment> known(Ids.TenantId tenant, String sessionId) {
        try {
            return service.forSession(tenant, sessionId);
        } catch (RuntimeException e) {
            LOG.debug("attachments of {} unavailable: {}", sessionId, e.toString());
            return Map.of();
        }
    }

    /** True when the recorded user turn carries attachment metadata. */
    public static boolean hasAttachments(Turn t) {
        return t.metadata() != null && t.metadata().get(TURN_META_KEY) instanceof List<?> l && !l.isEmpty();
    }

    /**
     * The turn's attachments for the panel: the recorded metadata (name, kind,
     * transcript, codes) with a fresh signed link per render, or no link when
     * retention removed the bytes.
     */
    @SuppressWarnings("unchecked")
    public List<SessionDtos.AttachmentView> views(Turn t, Ids.TenantId tenant,
                                                  Map<String, ChatAttachmentService.ChatAttachment> known) {
        if (!hasAttachments(t)) return List.of();
        List<SessionDtos.AttachmentView> out = new ArrayList<>();
        for (Object o : (List<?>) t.metadata().get(TURN_META_KEY)) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            Map<String, Object> m = (Map<String, Object>) raw;
            String id = String.valueOf(m.get("id"));
            ChatAttachmentService.ChatAttachment live = known == null ? null : known.get(id);
            String url = live != null && live.hasBlob() ? service.signedPath(tenant, id) : null;
            List<Map<String, Object>> codes = m.get("codes") instanceof List<?> l
                    ? l.stream().filter(c -> c instanceof Map<?, ?>).map(c -> (Map<String, Object>) c).toList()
                    : List.of();
            out.add(new SessionDtos.AttachmentView(id,
                    String.valueOf(m.get("kind")),
                    m.get("filename") == null ? null : String.valueOf(m.get("filename")),
                    m.get("contentType") == null ? null : String.valueOf(m.get("contentType")),
                    m.get("sizeBytes") instanceof Number n ? n.longValue() : 0,
                    m.get("transcript") == null ? (live == null ? null : live.transcript()) : String.valueOf(m.get("transcript")),
                    m.get("durationSeconds") instanceof Number n ? n.intValue() : (live == null ? null : live.durationSeconds()),
                    codes, url));
        }
        return out;
    }

    /** The upload response of contract C. */
    public SessionDtos.AttachmentResponse response(Ids.TenantId tenant, ChatAttachmentService.ChatAttachment a) {
        return new SessionDtos.AttachmentResponse(a.id(), a.kind(), a.filename(), a.contentType(), a.sizeBytes(),
                a.transcript(), a.durationSeconds(),
                a.codes().stream().map(c -> c.toMap()).toList(),
                a.extractedText() == null ? 0 : a.extractedText().length(),
                a.hasBlob() ? service.signedPath(tenant, a.id()) : null);
    }

    /** A message that names attachments it may not use. */
    static ChatApiException toApi(ChatAttachmentService.RejectedException e) {
        HttpStatus status = switch (e.code()) {
            case "not_found" -> HttpStatus.NOT_FOUND;
            case "too_many" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_REQUEST;
        };
        return new ChatApiException(status, "not_found".equals(e.code()) ? "attachment_not_found" : e.code(),
                e.getMessage(), 0);
    }
}
