package com.yayatechandinnovations.yayaagentic.channel.network;

import com.yayatechandinnovations.yayaagentic.channel.network.HubBrokerClient.BrokerError;
import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.engine.banca.BancaContext;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.tool.ToolException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared concerns for the network Bean tools (channel/network) —
 * yaya-agentic-network-chat-runtime-design.md §4/§4.1/§6:
 *
 * <ul>
 *   <li>the verified phone always comes from the session principal, never a model
 *       arg (§4/§6);</li>
 *   <li>{@code sources[]} coverage disclosure is enforced structurally — a non-OK
 *       source sets a per-turn model directive AND tags the result
 *       {@code coverage: "partial"} (§4.1);</li>
 *   <li>broker errors map to user-safe copy the model paraphrases, distinct from
 *       the audit line (§4.1, mirrors the two-reason AuthZ rule).</li>
 * </ul>
 */
@Component
public class NetworkToolSupport {

    /** The verified-phone claim minted by the chat auth resolver / device pass. */
    public static final String PHONE_CLAIM = "phone";

    private final WorkingMemory workingMemory;

    public NetworkToolSupport(WorkingMemory workingMemory) {
        this.workingMemory = workingMemory;
    }

    /**
     * The player's verified phone, from the session principal's claims — NEVER a
     * tool arg. Absence is a hard error (a network read/play must not run against
     * an unverified session); the network entry point requires a verified phone.
     */
    public String requireVerifiedPhone(ExecutionContext ctx) {
        Object phone = ctx.principal() == null || ctx.principal().claims() == null
                ? null : ctx.principal().claims().get(PHONE_CLAIM);
        if (phone == null || String.valueOf(phone).isBlank()) {
            throw new ToolException("network tool requires a verified phone on the session");
        }
        return String.valueOf(phone);
    }

    /** The opaque session reference every broker call carries for the hub audit
     *  ledger (§6). */
    public String sessionRef(ExecutionContext ctx) {
        return ctx.sessionId() == null ? "" : ctx.sessionId().value();
    }

    /**
     * Apply the §4.1 coverage contract to an aggregation result before returning
     * it. Inspects {@code sources} (each entry a map with a {@code status} and a
     * {@code banca}/{@code name}); when any is not {@code OK}, sets the per-turn
     * {@code COVERAGE_DIRECTIVE_SLOT} so the prompt builder forces disclosure, and
     * returns {@code "partial"} (else {@code "complete"}). The caller puts the
     * returned value on the result under {@code coverage} as a backstop.
     */
    @SuppressWarnings("unchecked")
    public String applyCoverage(ExecutionContext ctx, List<?> sources) {
        if (sources == null || sources.isEmpty()) return "complete";
        List<String> failed = sources.stream()
                .filter(s -> s instanceof Map)
                .map(s -> (Map<String, Object>) s)
                .filter(s -> !"OK".equalsIgnoreCase(String.valueOf(s.getOrDefault("status", "OK"))))
                .map(s -> {
                    String name = String.valueOf(s.getOrDefault("banca",
                            s.getOrDefault("name", s.getOrDefault("installCode", "una banca"))));
                    String status = String.valueOf(s.getOrDefault("status", "UNKNOWN"));
                    return name + " (" + status + ")";
                })
                .collect(Collectors.toList());
        if (failed.isEmpty()) return "complete";
        String directive = "coverage incomplete: " + String.join(", ", failed)
                + " — you MUST tell the player, by name, which banca(s) could not be checked "
                + "this time; do NOT present the numbers as complete.";
        workingMemory.merge(ctx.sessionId(), Map.of(BancaContext.COVERAGE_DIRECTIVE_SLOT, directive));
        return "partial";
    }

    /**
     * User-safe copy for a broker error (§4.1). The model paraphrases this; the
     * raw error name is the audit line, logged separately by the client.
     */
    public String userSafeReason(BrokerError error) {
        return switch (error) {
            case NOT_ENROLLED -> "Para ver esta información necesitas registrarte en el portal primero.";
            case NOT_LINKED -> "Aún no estás vinculado a esa banca. ¿Quieres vincularte?";
            case PLAY_DISABLED -> "Esa banca no tiene juego en línea disponible ahora mismo.";
            case TOKEN_EXPIRED -> "Tu sesión de juego expiró; vamos a refrescarla e intentar de nuevo.";
            case RATE_LIMITED -> "Estás yendo un poco rápido. Espera un momento e intenta de nuevo.";
            case BAD_REQUEST -> "No pude completar esa acción con los datos disponibles.";
            case UNAVAILABLE -> "No pude conectar con el servicio ahora mismo. Intenta de nuevo en un momento.";
        };
    }
}
