package com.yayatechandinnovations.yayaidentity.credentials;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

/**
 * The single {@link PasswordEncoder} for the service. It is a
 * {@link DelegatingPasswordEncoder} that ENCODES with Argon2id (design NFR-Sec-1
 * — passwords and OTP hashes) while still MATCHING any prefixed format, so the
 * same bean safely verifies client secrets too. Encoded values carry a
 * {@code {argon2}}/{@code {bcrypt}} id, so the algorithm can evolve without a
 * migration.
 */
@Configuration
public class SecurityCryptoConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        Argon2PasswordEncoder argon2 = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        Map<String, PasswordEncoder> encoders = Map.of(
                "argon2", argon2,
                "bcrypt", new BCryptPasswordEncoder());
        DelegatingPasswordEncoder delegating = new DelegatingPasswordEncoder("argon2", encoders);
        // Match legacy/unprefixed values as argon2 rather than throwing.
        delegating.setDefaultPasswordEncoderForMatches(argon2);
        return delegating;
    }
}
