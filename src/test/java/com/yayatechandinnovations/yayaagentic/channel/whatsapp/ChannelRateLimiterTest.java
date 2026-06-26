package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelRateLimiterTest {

    @Test
    void sender_bucket_admits_up_to_burst_then_throttles() {
        // Burst 10, 5/s refill — pure throttling test.
        ChannelRateLimiter rl = new ChannelRateLimiter(5, 10, 60, 30);
        for (int i = 0; i < 10; i++) {
            assertThat(rl.allowSender("snd_1")).isTrue();
        }
        // 11th: bucket exhausted
        assertThat(rl.allowSender("snd_1")).isFalse();
    }

    @Test
    void different_senders_have_independent_buckets() {
        ChannelRateLimiter rl = new ChannelRateLimiter(5, 2, 60, 30);
        assertThat(rl.allowSender("snd_a")).isTrue();
        assertThat(rl.allowSender("snd_a")).isTrue();
        assertThat(rl.allowSender("snd_a")).isFalse();
        // Different sender — fresh bucket
        assertThat(rl.allowSender("snd_b")).isTrue();
    }

    @Test
    void end_user_bucket_is_keyed_by_sender_phone_tuple() {
        ChannelRateLimiter rl = new ChannelRateLimiter(100, 100, 60, 2);
        assertThat(rl.allowEndUser("snd_a", "+15551")).isTrue();
        assertThat(rl.allowEndUser("snd_a", "+15551")).isTrue();
        assertThat(rl.allowEndUser("snd_a", "+15551")).isFalse();
        // Different phone — independent bucket
        assertThat(rl.allowEndUser("snd_a", "+15552")).isTrue();
        // Different sender, same phone — independent bucket
        assertThat(rl.allowEndUser("snd_b", "+15551")).isTrue();
    }

    @Test
    void bucket_refills_over_time() throws InterruptedException {
        // Burst 1, 10/s refill — bucket needs ~100ms to refill 1 token.
        ChannelRateLimiter rl = new ChannelRateLimiter(10, 1, 60, 30);
        assertThat(rl.allowSender("snd_refill")).isTrue();
        // Immediately after — should be empty (only a few µs elapsed).
        assertThat(rl.allowSender("snd_refill")).isFalse();
        Thread.sleep(150);
        assertThat(rl.allowSender("snd_refill"))
                .as("Should refill after ~100ms at 10 tokens/s").isTrue();
    }
}
