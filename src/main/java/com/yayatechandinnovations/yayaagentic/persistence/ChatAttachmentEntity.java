package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One outbound chat attachment ({@code chat-experience-design.md} §6): the
 * logical, session-scoped handle over blob bytes. Created unreferenced by the
 * upload endpoint; stamped with the referencing turn on send; swept when never
 * referenced within the grace window.
 */
@Entity
@Table(name = "chat_attachments")
public class ChatAttachmentEntity {

    public static final String KIND_IMAGE = "IMAGE";
    public static final String KIND_AUDIO = "AUDIO";
    public static final String KIND_DOCUMENT = "DOCUMENT";

    @Id
    @Column(name = "id", nullable = false, length = 44)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "account_id")
    private UUID accountId;

    /** Null once the retention sweep removed the bytes; the row stays so the
     *  transcript still shows what was sent. */
    @Column(name = "blob_id", length = 128)
    private String blobId;

    /** Owner of a sessions-API upload (contract C): the daily byte ceiling is
     *  keyed on it the way the chat keys on {@code accountId}. */
    @Column(name = "principal_subject", length = 255)
    private String principalSubject;

    /** Codes read from an image: {@code [{"format":"QR_CODE","text":"…"}]}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "codes_json", columnDefinition = "jsonb")
    private String codesJson;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    @Column(name = "transcript")
    private String transcript;

    @Column(name = "extracted_text")
    private String extractedText;

    @Column(name = "turn_id", length = 64)
    private String turnId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "referenced_at")
    private OffsetDateTime referencedAt;

    protected ChatAttachmentEntity() {}

    public ChatAttachmentEntity(String id, String tenantId, String sessionId, UUID accountId,
                                String blobId, String filename, String contentType,
                                long sizeBytes, String sha256, String kind) {
        this.id = id;
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.accountId = accountId;
        this.blobId = blobId;
        this.filename = filename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.kind = kind;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getSessionId() { return sessionId; }
    public UUID getAccountId() { return accountId; }
    public String getBlobId() { return blobId; }
    public String getFilename() { return filename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getKind() { return kind; }
    public String getTranscript() { return transcript; }
    public String getExtractedText() { return extractedText; }
    public String getTurnId() { return turnId; }
    public String getPrincipalSubject() { return principalSubject; }
    public String getCodesJson() { return codesJson; }
    public Integer getDurationSeconds() { return durationSeconds; }
    public boolean hasBlob() { return blobId != null; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getReferencedAt() { return referencedAt; }

    public boolean isReferenced() { return turnId != null; }

    public void setTranscript(String transcript) { this.transcript = transcript; }
    public void setExtractedText(String extractedText) { this.extractedText = extractedText; }
    public void setPrincipalSubject(String principalSubject) { this.principalSubject = principalSubject; }
    public void setCodesJson(String codesJson) { this.codesJson = codesJson; }
    public void setDurationSeconds(Integer durationSeconds) { this.durationSeconds = durationSeconds; }

    /** The retention sweep took the bytes; the row is kept as a tombstone. */
    public void dropBlob() { this.blobId = null; }

    public void reference(String turnId) {
        this.turnId = turnId;
        this.referencedAt = OffsetDateTime.now();
    }
}
