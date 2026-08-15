package com.yayatechandinnovations.yayaagentic.channel.network.dev;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Dev-only stand-in for the Smart Lottery hub action plane, so the whole network
 * flow (verify phone → picker → quote → confirm → claim) can be exercised locally
 * with NO real hub. Serves canned data for the {@code /internal/agent/*} hub routes
 * and the install play-surface routes the vended credential points back at.
 *
 * <p>Gated OFF by default — enable with
 * {@code yaya.agentic.network.stub.enabled=true} (intended for
 * {@code SPRING_PROFILES_ACTIVE=local}). It can never wire up in production. Point
 * the tenant's network hub base-url at this server to use it.
 */
@RestController
@ConditionalOnProperty(name = "yaya.agentic.network.stub.enabled", havingValue = "true")
public class LocalHubStubController {

    /** The vended credential's base URL — where playSurface calls come back to. */
    @Value("${yaya.agentic.network.stub.base-url:http://localhost:8080}")
    private String stubBaseUrl;

    private static final Map<String, Object> LA_MINA = Map.of(
            "code", "LAMINA01", "name", "Banca La Mina", "city", "Santiago",
            "open", true, "juegaRemoto", true, "linked", "ACTIVE");
    private static final Map<String, Object> EL_SOL = Map.of(
            "code", "SOL02", "name", "Banca El Sol", "city", "Santo Domingo",
            "open", true, "juegaRemoto", true);

    @GetMapping("/internal/agent/players/{phone}/bancas")
    public Map<String, Object> bancas(@PathVariable String phone) {
        return Map.of("bancas", List.of(LA_MINA));
    }

    @GetMapping("/internal/agent/directory/online-bancas")
    public Map<String, Object> online(@RequestParam(required = false) Boolean openNow) {
        return Map.of("bancas", List.of(EL_SOL));
    }

    @GetMapping("/internal/agent/players/{phone}/overview")
    public Map<String, Object> overview(@PathVariable String phone) {
        return Map.of(
                "sources", List.of(Map.of("banca", "Banca La Mina", "status", "OK")),
                "bancas", List.of(Map.of("banca", "Banca La Mina",
                        "points", "1,250 pts", "cash", "RD$0.00",
                        "openTickets", 1, "unclaimedPrizes", 1)));
    }

    @GetMapping("/internal/agent/players/{phone}/prizes")
    public Map<String, Object> prizes(@PathVariable String phone) {
        return Map.of(
                "sources", List.of(Map.of("banca", "Banca La Mina", "status", "OK")),
                "prizes", List.of(Map.of("banca", "Banca La Mina", "installCode", "LAMINA01",
                        "ticketNumber", "A-123", "prizeDOP", "RD$450.00",
                        "claimableDOP", "RD$450.00", "claimStatus", "CLAIMABLE")));
    }

    @GetMapping("/internal/agent/players/{phone}/tickets")
    public Map<String, Object> tickets(@PathVariable String phone,
                                       @RequestParam(required = false) Integer limit) {
        return Map.of(
                "sources", List.of(Map.of("banca", "Banca La Mina", "status", "OK")),
                "tickets", List.of(Map.of("banca", "Banca La Mina",
                        "ticketNumber", "A-123", "amount", "RD$25.00", "draw", "hoy 9pm")));
    }

    @PostMapping("/internal/agent/play-session")
    public Map<String, Object> playSession(@RequestBody Map<String, Object> body) {
        String op = String.valueOf(body.getOrDefault("op", "play"));
        if ("link".equals(op)) {
            return Map.of("linked", "ACTIVE", "orgId", "org-1", "name", "Banca La Mina");
        }
        return Map.of(
                "orgId", "org-1", "displayName", "Banca La Mina", "linked", "ACTIVE",
                "playCredential", Map.of(
                        "baseUrl", stubBaseUrl, "customerId", "cust-stub",
                        "customerToken", "stub-token-do-not-log",
                        "expiresAt", Instant.now().plusSeconds(900).toString()));
    }

    // ---- install play surface (reached via the vended credential's baseUrl) ----

    @PostMapping("/player/play/quote")
    public Map<String, Object> quote(@RequestBody Map<String, Object> body) {
        return Map.of("quoteId", "Q-stub",
                "lines", List.of(Map.of("numbers", body.getOrDefault("numbers", "12-34"), "amount", "RD$25.00")),
                "amount", "RD$25.00", "draw", "hoy 9pm", "cutoff", "20:45", "balanceAfter", "RD$0.00");
    }

    @PostMapping("/player/play/execute")
    public Map<String, Object> execute(@RequestBody Map<String, Object> body) {
        return Map.of("ticketNumber", "T-stub-001", "status", "CONFIRMED", "draw", "hoy 9pm");
    }

    @PostMapping("/player/prizes/claim")
    public Map<String, Object> claim(@RequestBody Map<String, Object> body) {
        return Map.of("status", "PAID", "paidDOP", "RD$450.00",
                "ticketNumber", body.getOrDefault("ticketNumber", "A-123"));
    }
}
