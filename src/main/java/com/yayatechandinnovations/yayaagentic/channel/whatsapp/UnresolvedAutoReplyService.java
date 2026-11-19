package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBinding;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelAutoReplyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code OnUnresolvedPolicy.AUTO_REPLY}: the one fixed line a binding says to
 * a caller its resolver does not know, at most once per (sender, phone) per
 * 24 h. The message itself is still dropped by the worker exactly as under
 * REJECT — this only decides whether the line goes out and sends it on the
 * same free-form path the STOP confirmation uses (the caller just wrote, so
 * the window is open).
 */
@Service
public class UnresolvedAutoReplyService {

    private static final Logger log = LoggerFactory.getLogger(UnresolvedAutoReplyService.class);

    /** How long one reply covers a caller before the line may be said again. */
    public static final Duration REPLY_INTERVAL = Duration.ofHours(24);

    private final ChannelAutoReplyRepository repo;
    private final OutboundDispatcher outbound;
    private final Clock clock;
    private final TransactionOperations ownTransaction;

    @Autowired
    public UnresolvedAutoReplyService(ChannelAutoReplyRepository repo, OutboundDispatcher outbound, Clock clock,
                                      PlatformTransactionManager transactions) {
        this(repo, outbound, clock, requiresNew(transactions));
    }

    /** @param ownTransaction how {@link #claim} runs; unit tests pass {@code TransactionOperations.withoutTransaction()} */
    UnresolvedAutoReplyService(ChannelAutoReplyRepository repo, OutboundDispatcher outbound, Clock clock,
                               TransactionOperations ownTransaction) {
        this.repo = repo;
        this.outbound = outbound;
        this.clock = clock;
        this.ownTransaction = ownTransaction;
    }

    private static TransactionOperations requiresNew(PlatformTransactionManager transactions) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /**
     * Say the binding's line to the caller if the binding is AUTO_REPLY and
     * this caller has not heard it in the last 24 h.
     *
     * @return true when a reply was sent (or attempted) now
     */
    public boolean replyIfDue(Ids.TenantId tenant, SenderId sender, String endUserPhone, ChannelBinding binding) {
        Optional<String> text = binding == null ? Optional.empty() : binding.autoReplyText();
        if (text.isEmpty() || endUserPhone == null || endUserPhone.isBlank()) return false;
        if (!claim(sender, endUserPhone)) return false;
        try {
            outbound.dispatchGreeting(tenant, sender, endUserPhone,
                    new Ids.SessionId(UUID.randomUUID().toString()), text.get(), List.of());
        } catch (RuntimeException e) {
            log.warn("auto-reply to unknown caller not sent (sender {}): {}", sender.value(), e.getMessage());
        }
        return true;
    }

    /**
     * Record "replied now" unless a reply within the interval already stands.
     * Its own REQUIRES_NEW transaction (an explicit template, not a method
     * annotation: {@link #replyIfDue} calls this on {@code this}, where a
     * proxy annotation would not apply), so the claim survives the worker's
     * rollback-free drop of the inbound. One atomic upsert, so a racing
     * second worker gets "already claimed" instead of a duplicate-key error
     * — which would have left the transaction rollback-only and thrown
     * {@code UnexpectedRollbackException} out of the worker.
     */
    public boolean claim(SenderId sender, String endUserPhone) {
        OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        Integer rows = ownTransaction.execute(status ->
                repo.claim(sender.value(), endUserPhone, now, now.minus(REPLY_INTERVAL)));
        return rows != null && rows == 1;
    }
}
