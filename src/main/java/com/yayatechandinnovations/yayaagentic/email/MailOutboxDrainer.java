package com.yayatechandinnovations.yayaagentic.email;

import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.persistence.EmailOutboxEntity;
import com.yayatechandinnovations.yayaagentic.persistence.EmailOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Polls {@code email_outbox} for due messages and hands each to the
 * configured {@link MailSender}. Transient failures bump {@code attempts}
 * and reschedule with exponential backoff; permanent failures DEAD-letter.
 *
 * <p>Single-instance assumption — fine for now. When we scale beyond one
 * pod, switch to {@code SELECT … FOR UPDATE SKIP LOCKED} per row.</p>
 */
@Component
public class MailOutboxDrainer {

    private static final Logger log = LoggerFactory.getLogger(MailOutboxDrainer.class);

    private final EmailOutboxRepository repo;
    private final MailSender sender;
    private final int maxRetries;
    private final Duration backoffInitial;
    private final double backoffMultiplier;
    private final int batchSize;

    public MailOutboxDrainer(EmailOutboxRepository repo,
                             @Lazy MailSender sender,
                             YayaAgenticProperties props) {
        this.repo = repo;
        this.sender = sender;
        YayaAgenticProperties.Email.Outbox outbox = outboxOf(props);
        this.maxRetries = outbox.maxRetries();
        this.backoffInitial = outbox.backoffInitial();
        this.backoffMultiplier = outbox.backoffMultiplier();
        this.batchSize = outbox.batchSize();
    }

    @Scheduled(fixedDelayString = "${yaya.agentic.email.outbox.drainer-fixed-rate:PT60S}")
    public void drain() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<EmailOutboxEntity> due = repo.findReady(now, PageRequest.of(0, batchSize));
        for (EmailOutboxEntity row : due) {
            processOne(row, now);
        }
    }

    @Transactional
    void processOne(EmailOutboxEntity row, OffsetDateTime now) {
        try {
            sender.send(new MailMessage(
                    row.getToAddress(),
                    row.getSubject(),
                    row.getBodyText(),
                    row.getBodyHtml(),
                    row.getTemplateId(),
                    row.getIdempotencyKey()));
            row.setStatus("SENT");
            row.setSentAt(now);
            row.setLastError(null);
            repo.save(row);
        } catch (MailSendException ex) {
            int attempts = row.getAttempts() + 1;
            row.setAttempts(attempts);
            row.setLastError(truncate(ex.getMessage(), 1024));
            if (!ex.isTransient() || attempts >= maxRetries) {
                row.setStatus("DEAD");
                log.warn("[MAIL] DEAD-letter template={} to={} after {} attempts: {}",
                        row.getTemplateId(), row.getToAddress(), attempts, ex.getMessage());
            } else {
                row.setStatus("RETRYING");
                row.setNextAttemptAt(now.plus(backoffFor(attempts)));
            }
            repo.save(row);
        } catch (Exception ex) {
            // Defensive: any non-MailSendException is treated as a permanent
            // bug — DEAD-letter to surface it without retrying forever.
            row.setAttempts(row.getAttempts() + 1);
            row.setLastError(truncate(ex.getClass().getName() + ": " + ex.getMessage(), 1024));
            row.setStatus("DEAD");
            log.error("[MAIL] unexpected sender error template={} to={}: {}",
                    row.getTemplateId(), row.getToAddress(), ex.toString(), ex);
            repo.save(row);
        }
    }

    private Duration backoffFor(int attempts) {
        double seconds = backoffInitial.toSeconds() * Math.pow(backoffMultiplier, Math.max(0, attempts - 1));
        return Duration.ofSeconds((long) Math.min(seconds, Duration.ofHours(1).toSeconds()));
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static YayaAgenticProperties.Email.Outbox outboxOf(YayaAgenticProperties props) {
        YayaAgenticProperties.Email email = props.email();
        if (email == null || email.outbox() == null) {
            return YayaAgenticProperties.Email.Outbox.defaults();
        }
        return email.outbox();
    }
}
