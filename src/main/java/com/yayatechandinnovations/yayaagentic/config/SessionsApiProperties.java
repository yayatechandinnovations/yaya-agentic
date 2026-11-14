package com.yayatechandinnovations.yayaagentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Knobs for {@code /v1/sessions/*} when it is driven by a signed-in user's own
 * token ({@code docs/guides/admin-panel-sessions.md}).
 *
 * <ul>
 *   <li>{@code messagesPerMinute} — messages one principal may send per minute
 *       (default 20); the same ceiling applies, separately, to action taps.</li>
 *   <li>{@code sessionStartsPerMinute} — sessions one principal may start per
 *       minute (default 10). A start can run a start flow and have the model
 *       write the opening, so it has its own ceiling.</li>
 *   <li>{@code extraOrigins} — browser origins accepted for every tenant on top
 *       of each tenant's own host (shared admin domains, local development).
 *       {@code *} is a wildcard.</li>
 *   <li>{@code enforcePrincipalKinds} — a profile that declares
 *       {@code metadata.principalKinds} only opens for a principal of one of
 *       those kinds (default true).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "yaya.agentic.sessions")
public record SessionsApiProperties(Integer messagesPerMinute,
                                    Integer sessionStartsPerMinute,
                                    List<String> extraOrigins,
                                    Boolean enforcePrincipalKinds) {

    public int messagesPerMinuteOrDefault() {
        return messagesPerMinute == null || messagesPerMinute <= 0 ? 20 : messagesPerMinute;
    }

    public int sessionStartsPerMinuteOrDefault() {
        return sessionStartsPerMinute == null || sessionStartsPerMinute <= 0 ? 10 : sessionStartsPerMinute;
    }

    public List<String> extraOriginsOrEmpty() {
        return extraOrigins == null ? List.of()
                : extraOrigins.stream().filter(o -> o != null && !o.isBlank()).map(String::trim).toList();
    }

    public boolean enforcePrincipalKindsOrDefault() {
        return enforcePrincipalKinds == null || enforcePrincipalKinds;
    }
}
