package com.yayatechandinnovations.yayaagentic.config;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup.EmbeddedSignupProperties;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioProperties;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.BlobGcProperties;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.ClamAvProperties;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.KnowledgeUploadProperties;
import com.yayatechandinnovations.yayaagentic.knowledge.ingest.IngestionProperties;
import com.yayatechandinnovations.yayaagentic.operator_auth.apikeys.ApiKeysProperties;
import com.yayatechandinnovations.yayaagentic.operator_auth.apikeys.AuditProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.util.pattern.PathPatternParser;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({YayaAgenticProperties.class, TwilioProperties.class,
        EmbeddedSignupProperties.class,
        ApiKeysProperties.class, AuditProperties.class, KnowledgeUploadProperties.class,
        IngestionProperties.class, BlobGcProperties.class, ClamAvProperties.class,
        com.yayatechandinnovations.yayaagentic.outreach.push.PushProperties.class,
        com.yayatechandinnovations.yayaagentic.channel.chat.verify.ChatVerifyLimitsProperties.class,
        com.yayatechandinnovations.yayaagentic.structured.StructuredCompletionsProperties.class,
        SessionsApiProperties.class})
@EnableScheduling
public class CoreConfiguration {

    /**
     * Worker pool for inbound channel dispatch. Sits between the webhook
     * controller (which acks Twilio within 15s) and the engine. Sized for
     * a moderate-volume cluster; over-provisioned pools waste memory while
     * under-provisioned pools queue inbound. Tune via
     * {@code yaya.agentic.channels.dispatch.*}.
     */
    @Bean(name = "channelDispatchExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor channelDispatchExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setThreadNamePrefix("channel-dispatch-");
        exec.setCorePoolSize(8);
        exec.setMaxPoolSize(32);
        exec.setQueueCapacity(2000);
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(30);
        exec.initialize();
        return exec;
    }

    /**
     * Runs claimed knowledge-ingestion jobs off the scheduler thread. Small pool
     * — ingestion is embedding-API + JDBC bound, not CPU bound, and the queue is
     * durable so backpressure just leaves jobs QUEUED. Keep the pool ≥ the
     * ingestion batch size so a claimed batch never starves.
     */
    @Bean(name = "knowledgeIngestExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor knowledgeIngestExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setThreadNamePrefix("knowledge-ingest-");
        exec.setCorePoolSize(4);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(64);
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(60);
        exec.initialize();
        return exec;
    }

    /**
     * CORS for {@code /v1/**}. The operator console's origins come from
     * {@code yaya.agentic.cors.allowed-origins} and may send its session cookie.
     *
     * <p>{@code /v1/sessions/**} additionally answers a tenant's own app: the
     * origin of any live tenant, or a configured extra origin
     * ({@link com.yayatechandinnovations.yayaagentic.tenant.TenantOrigins}).
     * Those callers authenticate with a bearer token only, so their responses
     * never allow credentials — a cookie can not ride along. Which tenant an
     * origin may address is decided per request by {@code OriginEnforcer},
     * not here.
     */
    @Bean
    public CorsWebFilter corsWebFilter(YayaAgenticProperties props,
                                       com.yayatechandinnovations.yayaagentic.tenant.TenantOrigins tenantOrigins) {
        CorsConfiguration console = new CorsConfiguration();
        console.setAllowedOriginPatterns(props.cors().allowedOrigins());
        console.addAllowedMethod("*");
        console.addAllowedHeader("*");
        console.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource consoleSource = new UrlBasedCorsConfigurationSource(new PathPatternParser());
        consoleSource.registerCorsConfiguration("/v1/**", console);

        return new CorsWebFilter(exchange -> {
            String path = exchange.getRequest().getPath().pathWithinApplication().value();
            String origin = exchange.getRequest().getHeaders().getOrigin();
            if (origin != null && (path.equals("/v1/sessions") || path.startsWith("/v1/sessions/"))
                    && console.checkOrigin(origin) == null
                    && tenantOrigins.isKnown(origin)) {
                CorsConfiguration app = new CorsConfiguration();
                app.addAllowedOrigin(origin);
                app.setAllowedMethods(java.util.List.of("GET", "POST", "OPTIONS"));
                app.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "Accept",
                        "Cache-Control", "Last-Event-ID"));
                app.setExposedHeaders(java.util.List.of("Retry-After"));
                app.setAllowCredentials(false);
                app.setMaxAge(600L);
                return app;
            }
            return consoleSource.getCorsConfiguration(exchange);
        });
    }

    /**
     * Argon2id with parameters matching Spring Security 6 defaults
     * (16-byte salt, 32-byte hash, parallelism 1, memory 16 MiB, iters 2).
     * Single shared instance — the encoder is thread-safe and amortises the
     * BouncyCastle provider lookup.
     */
    @Bean
    public PasswordEncoder operatorPasswordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    /**
     * UTC clock for components that need time-of-now access and want it to
     * be substitutable in tests. New code should prefer injecting a Clock
     * over calling {@code Instant.now()} directly.
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
