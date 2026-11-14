package com.yayatechandinnovations.yayaagentic.usage;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * The refusal a conversational channel shows when a turn hits the monthly
 * allowance: one fixed sentence, never a model answer. Channels whose clients
 * read structured errors (the sessions API) map {@link QuotaExceededException}
 * themselves instead.
 */
public final class QuotaRefusals {

    private QuotaRefusals() {}

    public static String text(String language) {
        boolean es = language != null && language.toLowerCase(java.util.Locale.ROOT).startsWith("es");
        return es
                ? "Se agotaron los mensajes del asistente incluidos este mes. Vuelve a estar disponible al iniciar el próximo mes."
                : "This month's assistant messages are used up. It becomes available again when next month starts.";
    }

    /** Replaces a quota refusal with a spoken turn so text-only channels still answer. */
    public static Flux<TurnEvent> spoken(Flux<TurnEvent> turn) {
        return turn.onErrorResume(QuotaExceededException.class, e -> Flux.just(
                new TurnEvent.Token(text(e.language())),
                TurnEvent.End.of(new Ids.TurnId(UUID.randomUUID().toString()), 0, 0)));
    }
}
