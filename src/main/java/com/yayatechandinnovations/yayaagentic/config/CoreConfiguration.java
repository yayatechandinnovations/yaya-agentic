package com.yayatechandinnovations.yayaagentic.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.util.pattern.PathPatternParser;

@Configuration
@EnableConfigurationProperties(YayaAgenticProperties.class)
@EnableScheduling
public class CoreConfiguration {

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
}
