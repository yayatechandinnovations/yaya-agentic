package com.yayatechandinnovations.yayaagentic.channel.chat.media;

import com.yayatechandinnovations.yayaagentic.testsupport.TestMedia;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/** Container-level durations, so a note is refused and metered before the transcriber is paid. */
class AudioDurationsTest {

    @Test
    void readsEveryContainerTheClientsProduce() {
        assertThat(AudioDurations.parse(TestMedia.wav(14), "audio/wav")).hasValue(14);
        assertThat(AudioDurations.parse(TestMedia.oggOpus(14), "audio/ogg")).hasValue(14);
        assertThat(AudioDurations.parse(TestMedia.m4a(14), "audio/mp4")).hasValue(14);
        assertThat(AudioDurations.parse(TestMedia.webm(14.2), "audio/webm")).hasValue(15);
    }

    @Test
    void garbageIsEmpty() {
        assertThat(AudioDurations.parse(new byte[]{1, 2, 3}, "audio/ogg")).isEmpty();
        assertThat(AudioDurations.parse(null, "audio/ogg")).isEmpty();
        assertThat(AudioDurations.parse(new byte[64], "audio/mpeg")).isEmpty();
    }

    @Test
    void aClientHintIsTakenOnlyWhenPlausibleForTheBytes() {
        byte[] opaque = new byte[20_000];
        opaque[0] = 'x';
        // 20 kB at 14 s ≈ 11 kbps — a voice note
        assertThat(AudioDurations.resolve(opaque, "audio/aac", 14)).hasValue(14);
        // 20 kB claimed to be 2 hours — impossible
        assertThat(AudioDurations.resolve(opaque, "audio/aac", 7_200)).isEmpty();
        // the container wins over the hint
        assertThat(AudioDurations.resolve(TestMedia.wav(3), "audio/wav", 90)).isEqualTo(OptionalInt.of(3));
    }

    @Test
    void clockIsMinutesAndSeconds() {
        assertThat(AudioDurations.clock(14)).isEqualTo("0:14");
        assertThat(AudioDurations.clock(125)).isEqualTo("2:05");
    }
}
