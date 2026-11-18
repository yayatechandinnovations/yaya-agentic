package com.yayatechandinnovations.yayaagentic.channel.chat.media;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.MediaTranscriber;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.BlobStore;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.MalwareScanner;
import com.yayatechandinnovations.yayaagentic.knowledge.extract.ContentTypeSniffer;
import com.yayatechandinnovations.yayaagentic.knowledge.extract.TextExtractor;
import com.yayatechandinnovations.yayaagentic.outreach.media.MediaTokenSigner;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAttachmentEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAttachmentRepository;
import com.yayatechandinnovations.yayaagentic.usage.UsageMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Outbound chat media ({@code chat-experience-design.md} §6): the upload
 * gauntlet — kind allowlist over the SNIFFED type (never the declared one) →
 * per-kind size caps → malware scan → image hygiene → blob store — plus
 * upload-time enrichment (voice transcription, bounded document text
 * extraction, QR / barcode reading) and the sweeps (orphans after 24 h, sent
 * attachments' bytes after the retention window).
 *
 * <p>Two front doors share it: the public chat (admission by chat account) and
 * the sessions API (admission by the session's subject; contract C of the
 * assistant-channels tracker). Everything here mirrors the knowledge-upload
 * stance: an INFECTED verdict is a hard reject, unknown types are rejected by
 * kind classification, and the physical location is a {@link BlobStore}
 * concern the caller never sees.
 */
@Service
public class ChatAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<ImageCodes.Code>> CODES = new TypeReference<>() {};

    // §6.2 ceilings. Counts ride ChatRateLimiter at the controller; the byte
    // budget and size caps live here with the bytes.
    public static final long IMAGE_MAX_BYTES = 10L * 1024 * 1024;
    public static final long AUDIO_MAX_BYTES = 10L * 1024 * 1024;
    public static final long DOCUMENT_MAX_BYTES = 15L * 1024 * 1024;
    public static final long ACCOUNT_DAILY_BYTES = 150L * 1024 * 1024;
    public static final int MAX_PER_MESSAGE = 4;
    /** A voice note longer than this is refused, not cut (design §4.2 V5). */
    public static final int AUDIO_MAX_SECONDS = 120;
    public static final String AUDIO_TOO_LONG_HINT = "La nota de voz es muy larga (máximo 2 minutos)";
    public static final int DEFAULT_RETENTION_DAYS = 30;
    static final int DOC_TEXT_BUDGET_CHARS = 8_000;
    static final int ORPHAN_GRACE_HOURS = 24;

    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> HEIC_TYPES = Set.of(
            "image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence");
    // Browser voice notes arrive as Opus in a WebM container, and Tika sniffs
    // the WebM EBML header as video/webm even for audio-only files — so
    // video/webm classifies as AUDIO here. WebM is not otherwise accepted, the
    // audio size cap applies, and a video smuggled through just plays its
    // audio track and fails transcription gracefully.
    private static final Set<String> AUDIO_TYPES = Set.of(
            "audio/mp4", "audio/x-m4a", "audio/mpeg", "audio/ogg", "application/ogg",
            "audio/webm", "video/webm", "audio/vorbis", "audio/opus",
            "audio/x-opus+ogg", "audio/wav", "audio/x-wav", "audio/vnd.wave");
    private static final Set<String> DOCUMENT_TYPES = Set.of("application/pdf");

    private final ChatAttachmentRepository attachments;
    private final BlobStore blobStore;
    private final MalwareScanner scanner;
    private final ContentTypeSniffer sniffer;
    private final TextExtractor extractor;
    private final MediaTokenSigner signer;
    private final ObjectProvider<MediaTranscriber> transcribers;
    private final ObjectProvider<UsageMeter> usage;
    private final String publicBaseUrl;
    private final int retentionDays;

    @Autowired
    public ChatAttachmentService(ChatAttachmentRepository attachments,
                                 BlobStore blobStore,
                                 MalwareScanner scanner,
                                 ContentTypeSniffer sniffer,
                                 TextExtractor extractor,
                                 MediaTokenSigner signer,
                                 ObjectProvider<MediaTranscriber> transcribers,
                                 ObjectProvider<UsageMeter> usage,
                                 @Value("${yaya.agentic.public-base-url:http://localhost:8080}") String publicBaseUrl,
                                 @Value("${yaya.agentic.chat.attachments.retention-days:30}") int retentionDays) {
        this.attachments = attachments;
        this.blobStore = blobStore;
        this.scanner = scanner;
        this.sniffer = sniffer;
        this.extractor = extractor;
        this.signer = signer;
        this.transcribers = transcribers;
        this.usage = usage;
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        this.retentionDays = retentionDays <= 0 ? DEFAULT_RETENTION_DAYS : retentionDays;
    }

    /** No usage meter, default retention — hand-built callers and tests. */
    public ChatAttachmentService(ChatAttachmentRepository attachments,
                                 BlobStore blobStore,
                                 MalwareScanner scanner,
                                 ContentTypeSniffer sniffer,
                                 TextExtractor extractor,
                                 MediaTokenSigner signer,
                                 ObjectProvider<MediaTranscriber> transcribers,
                                 String publicBaseUrl) {
        this(attachments, blobStore, scanner, sniffer, extractor, signer, transcribers, null,
                publicBaseUrl, DEFAULT_RETENTION_DAYS);
    }

    /** Uniform, user-safe upload rejection. {@code hint} is a line for the person, when there is one. */
    public static class RejectedException extends RuntimeException {
        private final String code;
        private final String hint;

        public RejectedException(String code, String message) {
            this(code, message, null);
        }

        public RejectedException(String code, String message, String hint) {
            super(message);
            this.code = code;
            this.hint = hint;
        }

        public String code() { return code; }
        public String hint() { return hint; }
    }

    /**
     * Who is uploading: a chat account, or a sessions-API subject (contract C);
     * {@code profileId} is where media spend is reported; {@code language} is
     * what voice notes are expected in; {@code durationHint} is what the
     * client measured while recording (validated against the bytes).
     */
    public record Uploader(UUID accountId, String principalSubject, String profileId,
                           String language, Integer durationHint) {
        public static Uploader account(UUID accountId) {
            return new Uploader(accountId, null, null, null, null);
        }

        public static Uploader subject(String subject, String profileId, String language, Integer durationHint) {
            return new Uploader(null, subject, profileId, language, durationHint);
        }
    }

    public record ChatAttachment(String id, String kind, String contentType, String filename,
                                 long sizeBytes, String transcript, String extractedText,
                                 String signedUrl, List<ImageCodes.Code> codes, Integer durationSeconds) {
        public ChatAttachment {
            codes = codes == null ? List.of() : List.copyOf(codes);
        }

        public ChatAttachment(String id, String kind, String contentType, String filename,
                              long sizeBytes, String transcript, String extractedText, String signedUrl) {
            this(id, kind, contentType, filename, sizeBytes, transcript, extractedText, signedUrl, List.of(), null);
        }

        public boolean isImage() { return ChatAttachmentEntity.KIND_IMAGE.equals(kind); }
        public boolean isAudio() { return ChatAttachmentEntity.KIND_AUDIO.equals(kind); }
        public boolean isDocument() { return ChatAttachmentEntity.KIND_DOCUMENT.equals(kind); }
        /** The bytes are gone (retention) when there is no link to them. */
        public boolean hasBlob() { return signedUrl != null; }
    }

    /** An upload that went through; {@code transcriptionFailed} = audio kept, no transcript. */
    public record Uploaded(ChatAttachment attachment, boolean transcriptionFailed) {}

    /* ------------------------------- upload -------------------------------- */

    @Transactional
    public ChatAttachment upload(Ids.TenantId tenant, String sessionId, UUID accountId,
                                 String filename, byte[] bytes) {
        return upload(tenant, sessionId, Uploader.account(accountId), filename, bytes).attachment();
    }

    @Transactional
    public Uploaded upload(Ids.TenantId tenant, String sessionId, Uploader by, String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new RejectedException("empty_file", "the file is empty");
        }
        String contentType = sniffer.sniff(bytes, filename);
        String kind = classify(contentType, filename);
        long cap = switch (kind) {
            case ChatAttachmentEntity.KIND_IMAGE -> IMAGE_MAX_BYTES;
            case ChatAttachmentEntity.KIND_AUDIO -> AUDIO_MAX_BYTES;
            default -> DOCUMENT_MAX_BYTES;
        };
        if (bytes.length > cap) {
            throw new RejectedException("too_large",
                    "this file is too large (max " + (cap / (1024 * 1024)) + " MB)");
        }
        OffsetDateTime dayAgo = OffsetDateTime.now().minusHours(24);
        if (by.accountId() != null) {
            long recent = attachments.bytesUploadedSince(by.accountId(), dayAgo);
            if (recent + bytes.length > ACCOUNT_DAILY_BYTES) {
                throw new RejectedException("quota_exceeded",
                        "daily upload limit reached — try again tomorrow");
            }
        } else if (by.principalSubject() != null && !by.principalSubject().isBlank()) {
            long recent = attachments.bytesUploadedBySubjectSince(tenant.value(), by.principalSubject(), dayAgo);
            if (recent + bytes.length > ACCOUNT_DAILY_BYTES) {
                throw new RejectedException("quota_exceeded",
                        "daily upload limit reached — try again tomorrow");
            }
        }
        if (scanner.scan(bytes, filename) == MalwareScanner.Verdict.INFECTED) {
            throw new RejectedException("rejected", "this file cannot be sent");
        }

        Integer durationSeconds = null;
        List<ImageCodes.Code> codes = List.of();
        if (ChatAttachmentEntity.KIND_IMAGE.equals(kind)) {
            ImageHygiene.Normalized clean = ImageHygiene.normalize(bytes, contentType);
            bytes = clean.bytes();
            contentType = clean.contentType();
            codes = ImageCodes.decode(bytes);
        } else if (ChatAttachmentEntity.KIND_AUDIO.equals(kind)) {
            OptionalInt seconds = AudioDurations.resolve(bytes, contentType, by.durationHint());
            if (seconds.isPresent()) {
                if (seconds.getAsInt() > AUDIO_MAX_SECONDS) {
                    throw new RejectedException("too_large",
                            "voice notes are limited to " + AUDIO_MAX_SECONDS + " seconds", AUDIO_TOO_LONG_HINT);
                }
                durationSeconds = seconds.getAsInt();
            }
            UsageMeter meter = meter();
            if (meter != null && !meter.hasVoiceAllowance(tenant.value())) {
                throw new RejectedException("voice_quota_exceeded",
                        "this month's voice minutes are used up", "Este mes se agotaron los minutos de voz; escríbeme.");
            }
        }

        BlobStore.StoredBlob stored = blobStore.store(
                tenant, safeFilename(filename), contentType,
                new ByteArrayInputStream(bytes), bytes.length);

        String id = "chatatt_" + UUID.randomUUID().toString().replace("-", "");
        ChatAttachmentEntity e = new ChatAttachmentEntity(id, tenant.value(), sessionId,
                by.accountId(), stored.blobId(), safeFilename(filename), contentType,
                bytes.length, stored.sha256(), kind);
        e.setPrincipalSubject(by.principalSubject());
        e.setDurationSeconds(durationSeconds);
        if (!codes.isEmpty()) e.setCodesJson(writeCodes(codes));

        if (ChatAttachmentEntity.KIND_DOCUMENT.equals(kind)) {
            e.setExtractedText(extractBounded(bytes, filename, contentType));
        }
        e = attachments.save(e);

        boolean transcriptionFailed = false;
        if (ChatAttachmentEntity.KIND_AUDIO.equals(kind)) {
            transcriptionFailed = !transcribe(tenant, e, contentType, bytes, by);
        }
        return new Uploaded(toDomain(e, tenant), transcriptionFailed);
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
                        .filter(ChatAttachmentEntity::hasBlob)
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
                .filter(ChatAttachmentEntity::hasBlob)
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

    /** The same grant as a path only ({@code /v1/chat/media/<token>}), for clients that know the host. */
    public String signedPath(Ids.TenantId tenant, String attachmentId) {
        return "/v1/chat/media/" + signer.mint(tenant, attachmentId);
    }

    /** Attachment metadata for hydrating a session transcript. */
    @Transactional(readOnly = true)
    public List<ChatAttachment> forTurn(Ids.TenantId tenant, String sessionId, String turnId) {
        return attachments.findBySessionId(sessionId).stream()
                .filter(e -> turnId.equals(e.getTurnId()))
                .map(e -> toDomain(e, tenant))
                .toList();
    }

    /** Every attachment of a session, by id — one read per transcript render. */
    @Transactional(readOnly = true)
    public java.util.Map<String, ChatAttachment> forSession(Ids.TenantId tenant, String sessionId) {
        java.util.Map<String, ChatAttachment> out = new java.util.LinkedHashMap<>();
        for (ChatAttachmentEntity e : attachments.findBySessionId(sessionId)) {
            if (e.getTenantId().equals(tenant.value())) out.put(e.getId(), toDomain(e, tenant));
        }
        return out;
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

    /* ------------------------------- sweeps -------------------------------- */

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

    /**
     * Sent attachments keep their bytes for {@code retention-days}; after that
     * the blob goes and the row stays as a tombstone, so the transcript still
     * shows the name (design §3.6). Returns how many rows were tombstoned.
     */
    @Scheduled(fixedDelayString = "${yaya.agentic.chat.media.retention-interval-ms:3600000}",
            initialDelayString = "${yaya.agentic.chat.media.retention-initial-delay-ms:900000}")
    @Transactional
    public int sweepExpired() {
        try {
            List<ChatAttachmentEntity> expired =
                    attachments.findByTurnIdIsNotNullAndBlobIdIsNotNullAndReferencedAtBefore(
                            OffsetDateTime.now().minusDays(retentionDays));
            for (ChatAttachmentEntity e : expired) {
                String blobId = e.getBlobId();
                e.dropBlob();
                attachments.save(e);
                attachments.flush();
                deleteBlobIfUnreferenced(e.getTenantId(), blobId);
            }
            if (!expired.isEmpty()) {
                log.info("chat media retention removed the bytes of {} attachment(s) older than {} days",
                        expired.size(), retentionDays);
            }
            return expired.size();
        } catch (Exception e) {
            log.warn("chat media retention sweep failed: {}", e.toString());
            return 0;
        }
    }

    public int retentionDays() {
        return retentionDays;
    }

    /* ------------------------------ internals ------------------------------ */

    private void deleteRowAndMaybeBlob(ChatAttachmentEntity e) {
        attachments.delete(e);
        attachments.flush();
        if (e.getBlobId() != null) deleteBlobIfUnreferenced(e.getTenantId(), e.getBlobId());
    }

    private void deleteBlobIfUnreferenced(String tenantId, String blobId) {
        // Blobs are content-addressed and may be shared (same sha, other rows
        // or knowledge sources). Only drop the bytes when nothing else in the
        // chat table still points at them; the knowledge GC guards its own.
        boolean blobStillReferenced = attachments.countByTenantIdAndBlobId(tenantId, blobId) > 0;
        if (!blobStillReferenced) {
            try {
                blobStore.delete(new Ids.TenantId(tenantId), blobId);
            } catch (Exception ex) {
                log.warn("could not delete blob {}: {}", blobId, ex.toString());
            }
        }
    }

    /** True when a transcript was obtained. */
    private boolean transcribe(Ids.TenantId tenant, ChatAttachmentEntity e,
                               String contentType, byte[] bytes, Uploader by) {
        // A WebM voice note sniffs as video/webm (see AUDIO_TYPES); the
        // transcribers advertise audio/* — normalize before matching.
        String audioType = transcriberType(contentType);
        MediaTranscriber transcriber = transcribers.stream()
                .filter(t -> t.supportedContentTypes().contains(audioType))
                .findFirst().orElse(null);
        if (transcriber == null) {
            log.info("no transcriber for {} — voice note {} kept without transcript", audioType, e.getId());
            return false;
        }
        try {
            MediaTranscriber.TranscriptionContext ctx = new MediaTranscriber.TranscriptionContext(
                    tenant.value(), "chat", e.getId(),
                    Duration.ofSeconds(AUDIO_MAX_SECONDS), AUDIO_MAX_BYTES, null, by.language());
            MediaTranscriber.TranscriptionResult result;
            try {
                result = transcriber.transcribeBytes(bytes, audioType, ctx);
            } catch (UnsupportedOperationException urlOnly) {
                // The transcriber fetches a URL (the WhatsApp/BSP contract); hand it
                // the same signed grant a client would use.
                result = transcriber.transcribe(
                        new MediaTranscriber.InboundMediaRef(
                                java.net.URI.create(signedUrl(tenant, e.getId())), audioType, bytes.length),
                        ctx);
            }
            if (result.audioDuration() != null && result.audioDuration().toSeconds() > 0) {
                e.setDurationSeconds((int) result.audioDuration().toSeconds());
            }
            if (result.text() != null && !result.text().isBlank()) {
                e.setTranscript(result.text().trim());
            }
            attachments.save(e);
            UsageMeter meter = meter();
            if (meter != null && e.getDurationSeconds() != null) {
                meter.recordVoiceSeconds(tenant.value(), by.profileId(), e.getDurationSeconds());
            }
            return e.getTranscript() != null;
        } catch (Exception ex) {
            log.warn("voice transcription failed for {}: {}", e.getId(), ex.toString());
            return false;
        }
    }

    /** What the transcribers advertise: WebM voice notes sniff as video, WAV as vnd.wave. */
    static String transcriberType(String contentType) {
        String ct = bareType(contentType);
        if (ct.startsWith("video/webm")) return "audio/webm";
        if (ct.equals("audio/vnd.wave") || ct.equals("audio/x-wav")) return "audio/wav";
        if (ct.equals("application/ogg") || ct.equals("audio/vorbis") || ct.equals("audio/x-opus+ogg")) return "audio/ogg";
        if (ct.equals("audio/x-m4a")) return "audio/mp4";
        return ct;
    }

    private UsageMeter meter() {
        return usage == null ? null : usage.getIfAvailable();
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

    private static String classify(String contentType, String filename) {
        String ct = bareType(contentType);
        if (HEIC_TYPES.contains(ct) || looksHeic(filename)) {
            throw new RejectedException("unsupported_type",
                    "HEIC photos can't be read here", ImageHygiene.HEIC_HINT);
        }
        if (IMAGE_TYPES.contains(ct)) return ChatAttachmentEntity.KIND_IMAGE;
        if (AUDIO_TYPES.contains(ct)) return ChatAttachmentEntity.KIND_AUDIO;
        if (DOCUMENT_TYPES.contains(ct)) return ChatAttachmentEntity.KIND_DOCUMENT;
        throw new RejectedException("unsupported_type",
                "this file type can't be sent here");
    }

    /** Tika may sniff an iPhone original as a generic ISO container; the name settles it. */
    private static boolean looksHeic(String filename) {
        if (filename == null) return false;
        String f = filename.toLowerCase(Locale.ROOT);
        return f.endsWith(".heic") || f.endsWith(".heif");
    }

    private static String bareType(String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        int semi = ct.indexOf(';');
        if (semi >= 0) ct = ct.substring(0, semi);
        return ct.trim();
    }

    private static String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) return "attachment";
        String clean = filename.replaceAll("[\\r\\n\\\\/]", "_").trim();
        return clean.length() > 255 ? clean.substring(0, 255) : clean;
    }

    static String writeCodes(List<ImageCodes.Code> codes) {
        try {
            return JSON.writeValueAsString(codes.stream().map(ImageCodes.Code::toMap).toList());
        } catch (Exception e) {
            return null;
        }
    }

    static List<ImageCodes.Code> readCodes(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, CODES);
        } catch (Exception e) {
            return List.of();
        }
    }

    private ChatAttachment toDomain(ChatAttachmentEntity e, Ids.TenantId tenant) {
        return new ChatAttachment(e.getId(), e.getKind(), e.getContentType(), e.getFilename(),
                e.getSizeBytes(), e.getTranscript(), e.getExtractedText(),
                e.hasBlob() ? signedUrl(tenant, e.getId()) : null,
                readCodes(e.getCodesJson()), e.getDurationSeconds());
    }
}
