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
 *
 * <p>Keywords are matched in English and Spanish regardless of the sender's
 * locale (a Spanish-speaking user on an English sender still gets out). A
 * bare {@code cancelar} is deliberately NOT a stop word: in Spanish-speaking
 * tenants it is how a customer cancels an order or a pickup, so only the
 * unambiguous {@code cancelar suscripción} form counts. The confirmation copy
 * follows the sender's {@code defaultLocale} ({@link #stopConfirmation},
 * {@link #startConfirmation}).
 */
@Service
public class OptOutService {

    /** Confirmation copy returned to the user on STOP (English / non-Spanish senders). */
    public static final String STOP_CONFIRMATION = "You're unsubscribed. Reply START to resume.";
    /** Confirmation copy returned on START (English / non-Spanish senders). */
    public static final String START_CONFIRMATION = "You're resubscribed. We'll be in touch.";
    /** Spanish STOP confirmation, chosen when the sender's locale is {@code es*}. */
    public static final String STOP_CONFIRMATION_ES = "Ya no recibirás mensajes. Responde EMPEZAR para volver.";
    /** Spanish START confirmation. */
    public static final String START_CONFIRMATION_ES = "Volviste a suscribirte. Te escribimos pronto.";

    private static final Set<String> STOP_KEYWORDS  = Set.of(
            "stop", "unsub", "cancel", "quit",
            "parar", "baja", "salir", "cancelar suscripcion", "cancelar suscripción");
    private static final Set<String> START_KEYWORDS = Set.of(
            "start", "unstop", "subscribe",
            "empezar", "iniciar", "reanudar");

    private final ChannelOptOutRepository repo;

    public OptOutService(ChannelOptOutRepository repo) {
        this.repo = repo;
    }

    public boolean isOptedOut(SenderId sender, String endUserPhone) {
        return repo.findBySenderIdAndEndUserPhone(sender.value(), endUserPhone).isPresent();
    }

    @Transactional
    public void optOut(SenderId sender, String endUserPhone, String reason) {
        optOut(sender, endUserPhone, reason, null);
    }

    /**
     * @param attributedTenant on a platform sender, the workspace that wrote
     *                         to the phone last — a STOP counts against it
     *                         (whatsapp-shared-sender-design.md §9.4)
     */
    @Transactional
    public void optOut(SenderId sender, String endUserPhone, String reason, String attributedTenant) {
        repo.findBySenderIdAndEndUserPhone(sender.value(), endUserPhone)
                .or(() -> {
                    ChannelOptOutEntity row = new ChannelOptOutEntity(
                            sender.value(), endUserPhone, reason);
                    row.setAttributedTenantId(attributedTenant);
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
        String t = body.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (STOP_KEYWORDS.contains(t))  return Classification.STOP;
        if (START_KEYWORDS.contains(t)) return Classification.START;
        return Classification.NONE;
    }

    /** STOP confirmation in the sender's language ({@code es*} → Spanish, else English). */
    public static String stopConfirmation(String locale) {
        return isSpanish(locale) ? STOP_CONFIRMATION_ES : STOP_CONFIRMATION;
    }

    /** START confirmation in the sender's language ({@code es*} → Spanish, else English). */
    public static String startConfirmation(String locale) {
        return isSpanish(locale) ? START_CONFIRMATION_ES : START_CONFIRMATION;
    }

    static boolean isSpanish(String locale) {
        return locale != null && locale.trim().toLowerCase(Locale.ROOT).startsWith("es");
    }

    public enum Classification { STOP, START, NONE }
}
