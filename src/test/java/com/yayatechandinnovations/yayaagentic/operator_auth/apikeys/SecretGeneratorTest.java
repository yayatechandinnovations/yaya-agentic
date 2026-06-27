package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SecretGeneratorTest {

    private final SecretGenerator gen = new SecretGenerator();

    @Test
    void generates_well_formed_live_secrets() {
        for (int i = 0; i < 1000; i++) {
            String s = gen.generate();
            assertThat(s).startsWith("yk_live_");
            assertThat(s).hasSize(SecretGenerator.TOTAL_LEN);
            assertThat(gen.looksWellFormedLive(s)).isTrue();
        }
    }

    @Test
    void prefix_is_first_eleven_chars() {
        String s = "yk_live_abc12345xyz9q7w3e8r2t5y6u1i4o0p7a3s9";
        assertThat(gen.prefixOf(s)).isEqualTo("yk_live_abc");
        assertThat(gen.prefixOf(s)).hasSize(SecretGenerator.LOOKUP_PREFIX_LEN);
    }

    @Test
    void secrets_are_unique_across_many_draws() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(gen.generate())).as("collision is astronomically unlikely").isTrue();
        }
    }

    @Test
    void malformed_inputs_are_rejected_by_shape_gate() {
        assertThat(gen.looksWellFormedLive(null)).isFalse();
        assertThat(gen.looksWellFormedLive("yk_live_tooshort")).isFalse();
        assertThat(gen.looksWellFormedLive("yk_test_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).isFalse();
        assertThat(gen.looksWellFormedLive("yk_live_!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!")).isFalse();
        // 32 valid base62 chars after the prefix is the only accepted shape.
        assertThat(gen.looksWellFormedLive("yk_live_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345")).isTrue();
    }

    @Test
    void sha256_is_stable_and_32_bytes() {
        String s = gen.generate();
        assertThat(gen.sha256(s)).hasSize(32).isEqualTo(gen.sha256(s));
    }
}
