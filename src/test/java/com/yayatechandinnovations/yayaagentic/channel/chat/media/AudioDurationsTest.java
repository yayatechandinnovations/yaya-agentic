package com.yayatechandinnovations.yayaagentic.channel.chat.media;

import com.yayatechandinnovations.yayaagentic.testsupport.TestMedia;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

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

    /* ---------------- crafted containers: every walk ends, fast, empty ---------------- */

    @Test
    void aWavChunkSizeThatCastsNegativeDoesNotSpin() {
        // 0xFFFFFFF8 as a signed int is -8: the old step «8 + size» left the position unchanged.
        ByteBuffer b = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.ISO_8859_1)).putInt(56).put("WAVE".getBytes(StandardCharsets.ISO_8859_1));
        b.put("fmt ".getBytes(StandardCharsets.ISO_8859_1)).putInt(0xFFFFFFF8);
        OptionalInt out = assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> AudioDurations.parse(b.array(), "audio/wav"));
        assertThat(out).isEmpty();
    }

    @Test
    void aWebmElementSizeWhoseLowBitsCancelTheHeaderDoesNotSpin() {
        // Inside Info: a one-byte id (Void) with an 8-byte size 0x000000FFFFFFF7 — as an int
        // that is -9, exactly the header length, so «p = cs + cl» used to stay put.
        byte[] b = {
                0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x80,                                     // EBML, empty
                0x18, 0x53, (byte) 0x80, 0x67, 0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,                                     // Segment, unknown size
                0x15, 0x49, (byte) 0xA9, 0x66, (byte) 0x8D,                                             // Info, 13 bytes
                (byte) 0xEC, 0x01, 0x00, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xF7, // Void, bad size
                0, 0, 0, 0};
        OptionalInt out = assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> AudioDurations.parse(b, "audio/webm"));
        assertThat(out).isEmpty();
    }

    @Test
    void aZeroSizeMp4BoxEndsTheWalk() {
        ByteBuffer b = ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN);
        b.putInt(16).put("ftyp".getBytes(StandardCharsets.ISO_8859_1)).put("M4A ".getBytes(StandardCharsets.ISO_8859_1)).putInt(0);
        b.putInt(0).put("free".getBytes(StandardCharsets.ISO_8859_1)); // «to the end of the file», nothing inside
        OptionalInt out = assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> AudioDurations.parse(b.array(), "audio/mp4"));
        assertThat(out).isEmpty();
        // and a size below the header length is refused, not re-read forever
        ByteBuffer tiny = ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN);
        tiny.putInt(16).put("ftyp".getBytes(StandardCharsets.ISO_8859_1)).put("M4A ".getBytes(StandardCharsets.ISO_8859_1)).putInt(0);
        tiny.putInt(4).put("free".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> AudioDurations.parse(tiny.array(), "audio/mp4"))).isEmpty();
    }

    @Test
    void aTruncatedOggPageIsEmptyNotAnException() {
        byte[] truncated = new byte[20];
        System.arraycopy("OggS".getBytes(StandardCharsets.ISO_8859_1), 0, truncated, 0, 4);
        assertThat(assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> AudioDurations.parse(truncated, "audio/ogg"))).isEmpty();
        // a segment table that runs past the end
        byte[] overrun = new byte[40];
        System.arraycopy("OggS".getBytes(StandardCharsets.ISO_8859_1), 0, overrun, 0, 4);
        overrun[26] = (byte) 0xFF;
        assertThat(assertTimeoutPreemptively(Duration.ofMillis(500),
                () -> AudioDurations.parse(overrun, "audio/ogg"))).isEmpty();
    }

    @Test
    void clockIsMinutesAndSeconds() {
        assertThat(AudioDurations.clock(14)).isEqualTo("0:14");
        assertThat(AudioDurations.clock(125)).isEqualTo("2:05");
    }
}
