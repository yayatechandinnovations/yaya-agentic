package com.yayatechandinnovations.yayaagentic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "yaya.agentic")
public record YayaAgenticProperties(
        String defaultPersonality,
        String defaultTenant,
        String publicBaseUrl,
        Session session,
        Llm llm,
        Cors cors,
        Auth auth,
        HttpTools httpTools,
        OperatorAuth operatorAuth,
        UserAccounts userAccounts,
        Email email,
        Network network
) {
    // The record has a second (back-compat) constructor, so Spring's
    // configuration-property binding must be told which one to bind through —
    // the canonical one, which carries `network`.
    @ConstructorBinding
    public YayaAgenticProperties {
    }

    /** Back-compat: construct without the network block (defaults apply). */
    public YayaAgenticProperties(String defaultPersonality, String defaultTenant, String publicBaseUrl,
                                 Session session, Llm llm, Cors cors, Auth auth, HttpTools httpTools,
                                 OperatorAuth operatorAuth, UserAccounts userAccounts, Email email) {
        this(defaultPersonality, defaultTenant, publicBaseUrl, session, llm, cors, auth, httpTools,
                operatorAuth, userAccounts, email, null);
    }

    public Network network() {
        return network == null
                ? new Network(null, null, Network.Capabilities.defaults(), Network.RateLimits.defaults())
                : network;
    }

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

    /**
     * {@code allowedModels} is the list a profile's {@code model} is validated
     * against at registration. Empty = no profile may pin a model; every
     * profile runs on the deployment's.
     */
    public record Llm(String provider, List<String> allowedModels) {
        @ConstructorBinding
        public Llm {
        }

        public Llm(String provider) {
            this(provider, List.of());
        }

        public List<String> allowedModels() {
            return allowedModels == null ? List.of()
                    : allowedModels.stream().filter(m -> m != null && !m.isBlank()).map(String::trim).toList();
        }
    }

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
     * Corillo network entry point ({@code player_network_v1}) —
     * yaya-agentic-network-chat-runtime-design.md. The hub broker is the Smart
     * Lottery action plane the runtime calls with its own {@code X-Agent-Auth}
     * credential (never the chat principal); {@code capabilities} gate the
     * play/claim tools (both ship OFF until the platform chain is green);
     * {@code rateLimits} sit in front of any provider limits (§6).
     */
    public record Network(
            String seedTenant,
            Hub hub,
            Capabilities capabilities,
            RateLimits rateLimits
    ) {
        public Capabilities capabilities() {
            return capabilities == null ? Capabilities.defaults() : capabilities;
        }

        public RateLimits rateLimits() {
            return rateLimits == null ? RateLimits.defaults() : rateLimits;
        }

        /** {@code credentialRef} names the encrypted X-Agent-Auth secret (resolved
         *  via the tenant secret store), NOT the plaintext credential. */
        public record Hub(String baseUrl, String credentialRef, Duration timeout) {
            public Duration timeout() {
                return timeout == null ? Duration.ofSeconds(10) : timeout;
            }
        }

        /** Play-path and claim-path feature flags. Default OFF (design §4/§7 S3). */
        public record Capabilities(boolean playEnabled, boolean claimEnabled) {
            public static Capabilities defaults() {
                return new Capabilities(false, false);
            }
        }

        /**
         * App-level ceilings in front of any provider limits (§6). Per-session:
         * picker emissions, link confirms, play_confirm attempts. Per-phone
         * across sessions: broker calls.
         */
        public record RateLimits(
                int pickerPerSessionPerHour,
                int linkConfirmsPerSessionPerDay,
                int playConfirmsPerSessionPerHour,
                int brokerCallsPerPhonePerHour
        ) {
            public static RateLimits defaults() {
                return new RateLimits(30, 3, 10, 120);
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
