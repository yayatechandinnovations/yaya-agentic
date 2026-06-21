package com.yayatechandinnovations.yayaagentic.email;

import com.yayatechandinnovations.yayaagentic.persistence.EmailOutboxEntity;
import com.yayatechandinnovations.yayaagentic.persistence.EmailOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Entry point services use to schedule outbound mail. Always writes to the
 * outbox in the caller's transaction so signup / invite / reset get
 * at-least-once delivery even if the send fails downstream.
 *
 * <p>Idempotent on {@link MailMessage#idempotencyKey()} — a duplicate
 * enqueue (e.g. from a retried verify-email after a flaky request) is a
 * no-op rather than a constraint violation.</p>
 */
@Service
public class OutboxMailQueue {

    private final EmailOutboxRepository repo;

    public OutboxMailQueue(EmailOutboxRepository repo) {
        this.repo = repo;
    }

    /** Enqueue OR silently skip if a message with this idempotency key already exists. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void enqueue(MailMessage msg) {
        if (msg == null || msg.idempotencyKey() == null || msg.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("MailMessage.idempotencyKey is required");
        }
        if (repo.findByIdempotencyKey(msg.idempotencyKey()).isPresent()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        repo.save(new EmailOutboxEntity(
                msg.to(),
                msg.templateId(),
                msg.subject(),
                msg.bodyHtml(),
                msg.bodyText(),
                msg.idempotencyKey(),
                "PENDING",
                now,
                now));
    }
}
