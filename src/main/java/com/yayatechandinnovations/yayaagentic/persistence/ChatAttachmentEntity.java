package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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

    @Column(name = "blob_id", nullable = false, length = 128)
    private String blobId;

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
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getReferencedAt() { return referencedAt; }

    public boolean isReferenced() { return turnId != null; }

    public void setTranscript(String transcript) { this.transcript = transcript; }
    public void setExtractedText(String extractedText) { this.extractedText = extractedText; }

    public void reference(String turnId) {
        this.turnId = turnId;
        this.referencedAt = OffsetDateTime.now();
    }
}
