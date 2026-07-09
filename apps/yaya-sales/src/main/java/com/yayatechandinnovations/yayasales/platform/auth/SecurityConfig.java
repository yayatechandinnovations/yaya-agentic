package com.yayatechandinnovations.yayasales.platform.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * Trust boundary (design §15): yaya-sales trusts only the platform. Stateless;
 * health is open; PSP/carrier webhooks are "open" at the HTTP layer because
 * each handler verifies its provider signature and is idempotent (§10.3);
 * everything else requires an authenticated caller — a verified service token
 * (filter) or an issuer JWT (operator / forwarded shopper principal).
 *
 * <p>With no issuer configured, JWT auth is simply absent — endpoints stay
 * locked rather than open, and only service tokens can authenticate. The JWT
 * decoder is lazy ({@link SupplierJwtDecoder}) so boot never depends on the
 * issuer being reachable.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SalesAuthProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            SalesAuthProperties props,
                                            ServiceTokenFilter serviceTokenFilter,
                                            CourierTokenFilter courierTokenFilter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // No credentials is 401 (not Spring's default 403) even when JWT auth is off.
            .exceptionHandling(e -> e.authenticationEntryPoint(
                    new org.springframework.security.web.authentication.HttpStatusEntryPoint(
                            org.springframework.http.HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(serviceTokenFilter, BasicAuthenticationFilter.class)
            .addFilterBefore(courierTokenFilter, BasicAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/v1/sales/webhooks/**").permitAll()
                // §10.4: register/verify are the only public courier endpoints —
                // gated by the signed invite token and the phone OTP respectively.
                .requestMatchers("/v1/sales/delivery/agents/register",
                                 "/v1/sales/delivery/agents/verify").permitAll()
                .anyRequest().authenticated());

        if (props.jwtConfigured()) {
            http.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(
                    new SupplierJwtDecoder(() -> JwtDecoders.fromIssuerLocation(props.issuerUri())))));
        }

        return http.build();
    }
}
