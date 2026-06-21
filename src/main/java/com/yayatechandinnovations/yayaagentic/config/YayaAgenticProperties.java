package com.yayatechandinnovations.yayaagentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "yaya.agentic")
public record YayaAgenticProperties(
        String defaultPersonality,
        String defaultTenant,
        Session session,
        Llm llm,
        Cors cors,
        Auth auth,
        HttpTools httpTools,
        OperatorAuth operatorAuth,
        UserAccounts userAccounts,
        Email email
) {
    public record Session(Duration idleTimeout, Memory memory) {
        public Memory memory() {
            return memory == null ? Memory.defaults() : memory;
        }
    }

    /**
     * Cross-turn memory tuning. {@code tailWindow} is how many recent turns the
     * recorder returns under {@code LoadOptions.summarizedForPrompt} and how
     * many the prompt builder renders verbatim. {@code rotationThreshold} is
     * the unsummarized-tail size that triggers a post-commit summarization
     * pass — once exceeded, the recorder folds (count - tailWindow) oldest
     * turns into a single rolling summary on the session row.
     */
    public record Memory(int tailWindow, int rotationThreshold) {
        public static Memory defaults() {
            return new Memory(12, 20);
        }
    }

    public record Llm(String provider) {}

    public record Cors(List<String> allowedOrigins) {}

    public record HttpTools(
            List<String> egressAllowlist,
            boolean allowPrivateNetworks,
            Duration defaultTimeout
    ) {}

    public record Auth(
            boolean allowAnonymous,
            Oidc oidc,
            ServiceToken serviceToken,
            DelegatedHost delegatedHost
    ) {
        public record Oidc(List<Issuer> issuers, Duration jwksCacheTtl, Duration clockSkew) {
            public record Issuer(String iss, String jwksUri, List<String> audiences) {}
        }

        public record ServiceToken(Map<String, String> tenantSecrets) {}

        public record DelegatedHost(String publicKeyPem) {}
    }

    /**
     * Console-operator authentication. Distinct trust plane from {@link Auth},
     * which authenticates conversational callers. See
     * {@code docs/design/operator-auth-design.md} §2.
     */
    public record OperatorAuth(
            Bootstrap bootstrap,
            Session session
    ) {
        /**
         * Always-on break-glass operator. Resolution order:
         * {@code passwordHash} > {@code password} (hashed at boot) >
         * defaults ({@code admin}/{@code admin}, logged with a [SECURITY] warning).
         */
        public record Bootstrap(
                boolean enabled,
                String username,
                String password,
                String passwordHash
        ) {}

        /** Cookie + server-side-session knobs. */
        public record Session(
                String cookieName,
                Duration ttl,
                Duration slidingWindow,
                boolean cookieSecure
        ) {}
    }

    /**
     * Self-serve user accounts on the admin plane. See
     * {@code docs/specs/user-accounts/TRD.md} §6.1.
     */
    public record UserAccounts(
            boolean enabled,
            boolean firstUserIsPlatformAdmin,
            Password password,
            Verification verification,
            Reset reset,
            Invitation invitation,
            Sessions sessions
    ) {
        public record Password(int minLength, int classesRequired) {
            public static Password defaults() { return new Password(12, 3); }
        }

        public record Verification(Duration tokenTtl, Duration resendCooldown, int resendPerHourCap) {
            public static Verification defaults() {
                return new Verification(Duration.ofHours(24), Duration.ofSeconds(60), 5);
            }
        }

        public record Reset(Duration tokenTtl) {
            public static Reset defaults() { return new Reset(Duration.ofHours(1)); }
        }

        public record Invitation(Duration tokenTtl) {
            public static Invitation defaults() { return new Invitation(Duration.ofDays(7)); }
        }

        /** User-session and bootstrap-session TTLs differ — bootstrap is shorter for incident-response safety. */
        public record Sessions(Duration userTtl, Duration bootstrapTtl) {
            public static Sessions defaults() {
                return new Sessions(Duration.ofHours(12), Duration.ofHours(1));
            }
        }
    }

    /**
     * Outbound email — provider plus shared outbox tuning. Provider impls
     * live behind a {@code MailSender} bean; missing config drops mail to a
     * dev-console logger.
     */
    public record Email(
            boolean enabled,
            String provider,        // "sendgrid" | "dev-console"
            String from,
            String replyTo,
            SendGrid sendgrid,
            Outbox outbox
    ) {
        public record SendGrid(String apiKey) {}

        public record Outbox(
                int maxRetries,
                Duration backoffInitial,
                double backoffMultiplier,
                Duration drainerFixedRate,
                int batchSize
        ) {
            public static Outbox defaults() {
                return new Outbox(3, Duration.ofSeconds(30), 2.0, Duration.ofSeconds(60), 100);
            }
        }
    }
}
