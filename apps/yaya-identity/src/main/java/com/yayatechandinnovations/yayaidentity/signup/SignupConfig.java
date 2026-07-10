package com.yayatechandinnovations.yayaidentity.signup;

import com.yayatechandinnovations.yayaidentity.credentials.CredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Channel selection + the public security chain for {@code /signup/**}. Twilio
 * Verify when its three credentials are present, the local hashed-OTP channel
 * otherwise. The chain is permitAll/stateless/no-CSRF — every endpoint behind it
 * is designed for unauthenticated callers and does its own gating.
 */
@Configuration
@EnableConfigurationProperties(SignupProperties.class)
public class SignupConfig {

    private static final Logger log = LoggerFactory.getLogger(SignupConfig.class);

    @Bean
    OtpChannel otpChannel(SignupProperties props, CredentialService credentials,
                          RestClient.Builder restClientBuilder) {
        SignupProperties.Twilio twilio = props.twilio();
        if (twilio.configured()) {
            log.info("Signup OTP channel: Twilio Verify (service {})", twilio.verifyServiceSid());
            return new TwilioVerifyOtpChannel(restClientBuilder, twilio.baseUrl(),
                    twilio.accountSid(), twilio.authToken(), twilio.verifyServiceSid());
        }
        if (props.enabled() && !props.otpEchoEnabled()) {
            log.warn("Self-registration is ENABLED but no Twilio Verify credentials are set and "
                    + "otp-echo is off — signup OTPs will be undeliverable. Configure "
                    + "yaya.identity.signup.twilio.* or (dev only) otp-echo-enabled.");
        }
        return new LocalOtpChannel(credentials);
    }

    /** Before the SAS chain only in @Order; the matchers are disjoint. */
    @Bean
    @Order(0)
    SecurityFilterChain signupChain(HttpSecurity http,
            @Value("${yaya.identity.cors-allowed-origins:}") String corsAllowedOrigins)
            throws Exception {
        http
                .securityMatcher("/signup/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .cors(cors -> cors.configurationSource(corsSource(corsAllowedOrigins)))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS));
        return http.build();
    }

    private static org.springframework.web.cors.CorsConfigurationSource corsSource(String origins) {
        var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        if (StringUtils.hasText(origins)) {
            var config = new org.springframework.web.cors.CorsConfiguration();
            config.setAllowedOrigins(java.util.List.of(origins.split(",")));
            config.setAllowedMethods(java.util.List.of("GET", "POST", "OPTIONS"));
            config.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type"));
            source.registerCorsConfiguration("/**", config);
        }
        return source;
    }
}
