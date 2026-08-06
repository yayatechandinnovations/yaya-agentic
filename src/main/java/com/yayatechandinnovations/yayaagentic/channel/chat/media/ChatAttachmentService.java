package com.yayatechandinnovations.yayaagentic.channel.chat.media;

import com.yayatechandinnovations.yayaagentic.channel.MediaTranscriber;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.BlobStore;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.MalwareScanner;
import com.yayatechandinnovations.yayaagentic.knowledge.extract.ContentTypeSniffer;
import com.yayatechandinnovations.yayaagentic.knowledge.extract.TextExtractor;
import com.yayatechandinnovations.yayaagentic.outreach.media.MediaTokenSigner;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAttachmentEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAttachmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Outbound chat media ({@code chat-experience-design.md} §6): the upload
 * gauntlet — kind allowlist over the SNIFFED type (never the declared one) →
 * per-kind size caps → malware scan → blob store — plus upload-time enrichment
 * (voice transcription, bounded document text extraction) and the orphan sweep.
 *
 * <p>Everything here mirrors the knowledge-upload stance: an INFECTED verdict
 * is a hard reject, unknown types are rejected by kind classification, and the
 * physical location is a {@link BlobStore} concern the caller never sees.
 */
@Service
public class ChatAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentService.class);

    // §6.2 ceilings. Counts ride ChatRateLimiter at the controller; the byte
    // budget and size caps live here with the bytes.
    public static final long IMAGE_MAX_BYTES = 10L * 1024 * 1024;
    public static final long AUDIO_MAX_BYTES = 10L * 1024 * 1024;
    public static final long DOCUMENT_MAX_BYTES = 15L * 1024 * 1024;
    public static final long ACCOUNT_DAILY_BYTES = 150L * 1024 * 1024;
    public static final int MAX_PER_MESSAGE = 4;
    static final int DOC_TEXT_BUDGET_CHARS = 8_000;
    static final int ORPHAN_GRACE_HOURS = 24;

    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif");
    private static final Set<String> AUDIO_TYPES = Set.of(
            "audio/mp4", "audio/x-m4a", "audio/mpeg", "audio/ogg", "application/ogg",
            "audio/webm", "audio/vorbis", "audio/opus", "audio/wav", "audio/x-wav");
    private static final Set<String> DOCUMENT_TYPES = Set.of("application/pdf");

    private final ChatAttachmentRepository attachments;
    private final BlobStore blobStore;
    private final MalwareScanner scanner;
    private final ContentTypeSniffer sniffer;
    private final TextExtractor extractor;
    private final MediaTokenSigner signer;
    private final ObjectProvider<MediaTranscriber> transcribers;
    private final String publicBaseUrl;

    public ChatAttachmentService(ChatAttachmentRepository attachments,
                                 BlobStore blobStore,
                                 MalwareScanner scanner,
                                 ContentTypeSniffer sniffer,
                                 TextExtractor extractor,
                                 MediaTokenSigner signer,
                                 ObjectProvider<MediaTranscriber> transcribers,
                                 @Value("${yaya.agentic.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.attachments = attachments;
        this.blobStore = blobStore;
        this.scanner = scanner;
        this.sniffer = sniffer;
        this.extractor = extractor;
        this.signer = signer;
        this.transcribers = transcribers;
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
    }

    /** Uniform, user-safe upload rejection. */
    public static class RejectedException extends RuntimeException {
        private final String code;

        public RejectedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }

    public record ChatAttachment(String id, String kind, String contentType, String filename,
                                 long sizeBytes, String transcript, String extractedText,
                                 String signedUrl) {
        public boolean isImage() { return ChatAttachmentEntity.KIND_IMAGE.equals(kind); }
        public boolean isAudio() { return ChatAttachmentEntity.KIND_AUDIO.equals(kind); }
        public boolean isDocument() { return ChatAttachmentEntity.KIND_DOCUMENT.equals(kind); }
    }

    /* ------------------------------- upload -------------------------------- */

    @Transactional
    public ChatAttachment upload(Ids.TenantId tenant, String sessionId, UUID accountId,
                                 String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new RejectedException("empty_file", "the file is empty");
        }
        String contentType = sniffer.sniff(bytes, filename);
        String kind = classify(contentType);
        long cap = switch (kind) {
            case ChatAttachmentEntity.KIND_IMAGE -> IMAGE_MAX_BYTES;
            case ChatAttachmentEntity.KIND_AUDIO -> AUDIO_MAX_BYTES;
            default -> DOCUMENT_MAX_BYTES;
        };
        if (bytes.length > cap) {
            throw new RejectedException("too_large",
                    "this file is too large (max " + (cap / (1024 * 1024)) + " MB)");
        }
        if (accountId != null) {
            long recent = attachments.bytesUploadedSince(
                    accountId, OffsetDateTime.now().minusHours(24));
            if (recent + bytes.length > ACCOUNT_DAILY_BYTES) {
                throw new RejectedException("quota_exceeded",
                        "daily upload limit reached — try again tomorrow");
            }
        }
        if (scanner.scan(bytes, filename) == MalwareScanner.Verdict.INFECTED) {
            throw new RejectedException("rejected", "this file cannot be sent");
        }

        BlobStore.StoredBlob stored = blobStore.store(
                tenant, safeFilename(filename), contentType,
                new ByteArrayInputStream(bytes), bytes.length);

        String id = "chatatt_" + UUID.randomUUID().toString().replace("-", "");
        ChatAttachmentEntity e = new ChatAttachmentEntity(id, tenant.value(), sessionId,
                accountId, stored.blobId(), safeFilename(filename), contentType,
                bytes.length, stored.sha256(), kind);

        if (ChatAttachmentEntity.KIND_DOCUMENT.equals(kind)) {
            e.setExtractedText(extractBounded(bytes, filename, contentType));
        }
        e = attachments.save(e);

        if (ChatAttachmentEntity.KIND_AUDIO.equals(kind)) {
            transcribe(tenant, e, contentType, bytes.length);
        }
        return toDomain(e, tenant);
    }

    /* --------------------------- send resolution --------------------------- */

    /**
     * Resolve the attachments a message references: they must exist, belong to
     * THIS session, and be unreferenced (single-use — a sent attachment belongs
     * to its turn). Anything else is a uniform not-found.
     */
    @Transactional(readOnly = true)
    public List<ChatAttachment> resolveForSend(Ids.TenantId tenant, String sessionId,
                                               List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        if (ids.size() > MAX_PER_MESSAGE) {
            throw new RejectedException("too_many", "at most " + MAX_PER_MESSAGE
                    + " attachments per message");
        }
        List<ChatAttachment> out = new ArrayList<>();
        for (String id : ids) {
            ChatAttachmentEntity e = attachments.findById(id)
                    .filter(a -> a.getSessionId().equals(sessionId))
                    .filter(a -> !a.isReferenced())
                    .orElseThrow(() -> new RejectedException("not_found", "unknown attachment"));
            out.add(toDomain(e, tenant));
        }
        return out;
    }

    @Transactional
    public void markReferenced(List<String> ids, String turnId) {
        for (String id : ids) {
            attachments.findById(id).ifPresent(e -> {
                e.reference(turnId);
                attachments.save(e);
            });
        }
    }

    /* ------------------------------ serving -------------------------------- */

    /** Attachment behind a verified signed token — the streaming read. */
    @Transactional(readOnly = true)
    public Optional<StreamableAttachment> openSigned(String token) {
        return signer.verify(token).flatMap(grant ->
                attachments.findById(grant.assetId())
                        .filter(e -> e.getTenantId().equals(grant.tenant().value()))
                        .flatMap(e -> blobStore
                                .open(new Ids.TenantId(e.getTenantId()), e.getBlobId())
                                .map(in -> new StreamableAttachment(
                                        e.getContentType(), e.getFilename(), e.getSizeBytes(), in))));
    }

    public record StreamableAttachment(String contentType, String filename, long sizeBytes,
                                       InputStream stream) {}

    /** Bytes for the vision path (§6.3) — capped by the image size cap. */
    @Transactional(readOnly = true)
    public Optional<byte[]> readBytes(Ids.TenantId tenant, String attachmentId) {
        return attachments.findById(attachmentId)
                .filter(e -> e.getTenantId().equals(tenant.value()))
                .flatMap(e -> blobStore.open(tenant, e.getBlobId()))
                .map(in -> {
                    try (in) {
                        return in.readAllBytes();
                    } catch (Exception e) {
                        return null;
                    }
                });
    }

    /** Signed short-lived URL for client rendering (mirrors MediaController). */
    public String signedUrl(Ids.TenantId tenant, String attachmentId) {
        return publicBaseUrl + "/v1/chat/media/" + signer.mint(tenant, attachmentId);
    }

    /** Attachment metadata for hydrating a session transcript. */
    @Transactional(readOnly = true)
    public List<ChatAttachment> forTurn(Ids.TenantId tenant, String sessionId, String turnId) {
        return attachments.findBySessionId(sessionId).stream()
                .filter(e -> turnId.equals(e.getTurnId()))
                .map(e -> toDomain(e, tenant))
                .toList();
    }

    /* ---------------------------- account erase ---------------------------- */

    /** Delete every attachment (rows + blobs) an account ever uploaded — the
     *  account-deletion GDPR path (§6.3). */
    @Transactional
    public void deleteForAccount(UUID accountId) {
        for (ChatAttachmentEntity e : attachments.findByAccountId(accountId)) {
            deleteRowAndMaybeBlob(e);
        }
    }

    /* ------------------------------- sweep --------------------------------- */

    /** Uploaded-but-never-sent rows are reclaimed after the grace window. */
    @Scheduled(fixedDelayString = "${yaya.agentic.chat.media.gc-interval-ms:3600000}",
            initialDelayString = "${yaya.agentic.chat.media.gc-initial-delay-ms:600000}")
    @Transactional
    public void sweepOrphans() {
        try {
            List<ChatAttachmentEntity> orphans = attachments.findByTurnIdIsNullAndCreatedAtBefore(
                    OffsetDateTime.now().minusHours(ORPHAN_GRACE_HOURS));
            for (ChatAttachmentEntity e : orphans) {
                deleteRowAndMaybeBlob(e);
            }
            if (!orphans.isEmpty()) {
                log.info("chat media GC removed {} orphaned attachment(s)", orphans.size());
            }
        } catch (Exception e) {
            log.warn("chat media GC sweep failed: {}", e.toString());
        }
    }

    /* ------------------------------ internals ------------------------------ */

    private void deleteRowAndMaybeBlob(ChatAttachmentEntity e) {
        attachments.delete(e);
        attachments.flush();
        // Blobs are content-addressed and may be shared (same sha, other rows
        // or knowledge sources). Only drop the bytes when nothing else in the
        // chat table still points at them; the knowledge GC guards its own.
        boolean blobStillReferenced =
                attachments.countByTenantIdAndBlobId(e.getTenantId(), e.getBlobId()) > 0;
        if (!blobStillReferenced) {
            try {
                blobStore.delete(new Ids.TenantId(e.getTenantId()), e.getBlobId());
            } catch (Exception ex) {
                log.warn("could not delete blob {}: {}", e.getBlobId(), ex.toString());
            }
        }
    }

    private void transcribe(Ids.TenantId tenant, ChatAttachmentEntity e,
                            String contentType, long sizeBytes) {
        MediaTranscriber transcriber = transcribers.stream()
                .filter(t -> t.supportedContentTypes().contains(contentType))
                .findFirst().orElse(null);
        if (transcriber == null) return; // graceful: bubble still plays audio
        try {
            // The transcriber fetches a URL (the WhatsApp/BSP contract); hand it
            // the same signed grant a client would use.
            URI url = URI.create(signedUrl(tenant, e.getId()));
            MediaTranscriber.TranscriptionResult result = transcriber.transcribe(
                    new MediaTranscriber.InboundMediaRef(url, contentType, sizeBytes),
                    new MediaTranscriber.TranscriptionContext(
                            tenant.value(), "chat", e.getId(),
                            Duration.ofMinutes(2), AUDIO_MAX_BYTES, null));
            if (result.text() != null && !result.text().isBlank()) {
                e.setTranscript(result.text().trim());
                attachments.save(e);
            }
        } catch (Exception ex) {
            log.warn("voice transcription failed for {}: {}", e.getId(), ex.toString());
        }
    }

    private String extractBounded(byte[] bytes, String filename, String contentType) {
        try {
            TextExtractor.Extracted extracted = extractor.extract(
                    new ByteArrayInputStream(bytes), filename, contentType);
            StringBuilder sb = new StringBuilder();
            for (TextExtractor.Page page : extracted.pages()) {
                if (sb.length() >= DOC_TEXT_BUDGET_CHARS) break;
                if (page.text() != null) sb.append(page.text()).append('\n');
            }
            if (sb.isEmpty()) return null;
            return sb.length() > DOC_TEXT_BUDGET_CHARS
                    ? sb.substring(0, DOC_TEXT_BUDGET_CHARS) + "\n[truncated]"
                    : sb.toString();
        } catch (Exception e) {
            log.warn("document text extraction failed for {}: {}", filename, e.toString());
            return null;
        }
    }

    private static String classify(String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        int semi = ct.indexOf(';');
        if (semi >= 0) ct = ct.substring(0, semi).trim();
        if (IMAGE_TYPES.contains(ct)) return ChatAttachmentEntity.KIND_IMAGE;
        if (AUDIO_TYPES.contains(ct)) return ChatAttachmentEntity.KIND_AUDIO;
        if (DOCUMENT_TYPES.contains(ct)) return ChatAttachmentEntity.KIND_DOCUMENT;
        throw new RejectedException("unsupported_type",
                "this file type can't be sent here");
    }

    private static String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "attachment";
        String clean = filename.replaceAll("[\\r\\n\\\\/]", "_").trim();
        return clean.length() > 255 ? clean.substring(0, 255) : clean;
    }

    private ChatAttachment toDomain(ChatAttachmentEntity e, Ids.TenantId tenant) {
        return new ChatAttachment(e.getId(), e.getKind(), e.getContentType(), e.getFilename(),
                e.getSizeBytes(), e.getTranscript(), e.getExtractedText(),
                signedUrl(tenant, e.getId()));
    }
}
