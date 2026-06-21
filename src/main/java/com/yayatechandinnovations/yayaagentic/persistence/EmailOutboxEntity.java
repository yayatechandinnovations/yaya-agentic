package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * At-least-once mail send buffer. The drainer (M5a phase 2) picks rows
 * where {@code status IN ('PENDING','RETRYING') AND next_attempt_at <= now()}
 * and invokes the configured {@code MailSender}.
 *
 * <p>{@code idempotencyKey} is sha256(templateId + tokenHash) for token-
 * bearing mails so a re-enqueue (e.g. from a retried signup) doesn't
 * double-send. The unique index on the column is the enforcement.</p>
 */
@Entity
@Table(name = "email_outbox")
public class EmailOutboxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false)
    private Long id;

    @Column(name = "to_address", nullable = false)
    private String toAddress;

    @Column(name = "template_id", nullable = false)
    private String templateId;

    @Column(nullable = false)
    private String subject;

    @Column(name = "body_html")
    private String bodyHtml;

    @Column(name = "body_text", nullable = false)
    private String bodyText;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private OffsetDateTime nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected EmailOutboxEntity() {}

    public EmailOutboxEntity(String toAddress, String templateId, String subject,
                             String bodyHtml, String bodyText, String idempotencyKey,
                             String status, OffsetDateTime nextAttemptAt,
                             OffsetDateTime createdAt) {
        this.toAddress = toAddress;
        this.templateId = templateId;
        this.subject = subject;
        this.bodyHtml = bodyHtml;
        this.bodyText = bodyText;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.attempts = 0;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getToAddress() { return toAddress; }
    public String getTemplateId() { return templateId; }
    public String getSubject() { return subject; }
    public String getBodyHtml() { return bodyHtml; }
    public String getBodyText() { return bodyText; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public OffsetDateTime getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public OffsetDateTime getSentAt() { return sentAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setStatus(String status) { this.status = status; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public void setNextAttemptAt(OffsetDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public void setSentAt(OffsetDateTime sentAt) { this.sentAt = sentAt; }
}
