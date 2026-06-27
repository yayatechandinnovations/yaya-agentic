package com.yayatechandinnovations.yayaagentic.config;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioProperties;
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
@EnableConfigurationProperties({YayaAgenticProperties.class, TwilioProperties.class})
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

    @Bean
    public CorsWebFilter corsWebFilter(YayaAgenticProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(props.cors().allowedOrigins());
        cfg.addAllowedMethod("*");
        cfg.addAllowedHeader("*");
        cfg.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource(new PathPatternParser());
        src.registerCorsConfiguration("/v1/**", cfg);
        return new CorsWebFilter(src);
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
