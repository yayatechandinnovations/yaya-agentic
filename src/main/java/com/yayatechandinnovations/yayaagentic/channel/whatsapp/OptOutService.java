package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.persistence.ChannelOptOutEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelOptOutRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * STOP/UNSUB/CANCEL/QUIT keyword handling per Meta policy + design §11.2.
 * Mandatory + non-disable-able: outbound dispatch consults
 * {@link #isOptedOut} before every send, and the inbound worker checks
 * {@link #classify} before invoking the engine.
 */
@Service
public class OptOutService {

    /** Confirmation copy returned to the user on STOP. */
    public static final String STOP_CONFIRMATION = "You're unsubscribed. Reply START to resume.";
    /** Confirmation copy returned on START. */
    public static final String START_CONFIRMATION = "You're resubscribed. We'll be in touch.";

    private static final Set<String> STOP_KEYWORDS  = Set.of("stop", "unsub", "cancel", "quit");
    private static final Set<String> START_KEYWORDS = Set.of("start", "unstop", "subscribe");

    private final ChannelOptOutRepository repo;

    public OptOutService(ChannelOptOutRepository repo) {
        this.repo = repo;
    }

    public boolean isOptedOut(SenderId sender, String endUserPhone) {
        return repo.findBySenderIdAndEndUserPhone(sender.value(), endUserPhone).isPresent();
    }

    @Transactional
    public void optOut(SenderId sender, String endUserPhone, String reason) {
        repo.findBySenderIdAndEndUserPhone(sender.value(), endUserPhone)
                .or(() -> {
                    ChannelOptOutEntity row = new ChannelOptOutEntity(
                            sender.value(), endUserPhone, reason);
                    repo.save(row);
                    return java.util.Optional.of(row);
                });
    }

    @Transactional
    public void resubscribe(SenderId sender, String endUserPhone) {
        repo.findBySenderIdAndEndUserPhone(sender.value(), endUserPhone)
                .ifPresent(repo::delete);
    }

    public List<ChannelOptOutEntity> listForSender(SenderId sender) {
        return repo.findBySenderId(sender.value());
    }

    public Classification classify(String body) {
        if (body == null) return Classification.NONE;
        String t = body.trim().toLowerCase(Locale.ROOT);
        if (STOP_KEYWORDS.contains(t))  return Classification.STOP;
        if (START_KEYWORDS.contains(t)) return Classification.START;
        return Classification.NONE;
    }

    public enum Classification { STOP, START, NONE }
}
