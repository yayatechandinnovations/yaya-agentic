package com.yayatechandinnovations.yayaagentic.channel.chat.media;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.OptionalInt;

/**
 * Container-level audio length, read from the bytes themselves so a voice
 * note can be refused before it is sent to the transcriber, and metered even
 * when the transcriber reports no duration. Covers what browsers, phones and
 * WhatsApp actually produce: OGG (Opus/Vorbis), WebM/Matroska, MP4/M4A, WAV
 * and MP3 (CBR estimate, Xing/Info header when present). Everything fails
 * soft to {@link OptionalInt#empty()}.
 */
public final class AudioDurations {

    private AudioDurations() {}

    /** Whole seconds, rounded up, or empty when the container says nothing usable. */
    public static OptionalInt parse(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length < 16) return OptionalInt.empty();
        try {
            double seconds = -1;
            if (startsWith(bytes, 0, "OggS")) {
                seconds = ogg(bytes);
            } else if ((bytes[0] & 0xFF) == 0x1A && (bytes[1] & 0xFF) == 0x45
                    && (bytes[2] & 0xFF) == 0xDF && (bytes[3] & 0xFF) == 0xA3) {
                seconds = webm(bytes);
            } else if (bytes.length >= 12 && startsWith(bytes, 4, "ftyp")) {
                seconds = mp4(bytes);
            } else if (startsWith(bytes, 0, "RIFF") && startsWith(bytes, 8, "WAVE")) {
                seconds = wav(bytes);
            } else if (startsWith(bytes, 0, "ID3") || isMp3Sync(bytes, 0)
                    || (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("mpeg"))) {
                seconds = mp3(bytes);
            }
            if (seconds <= 0 || Double.isNaN(seconds) || Double.isInfinite(seconds)) return OptionalInt.empty();
            return OptionalInt.of((int) Math.ceil(seconds));
        } catch (RuntimeException e) {
            return OptionalInt.empty();
        }
    }

    /**
     * The parsed duration when the container has one; otherwise a client hint
     * is accepted only when it is plausible for the byte count (between 4 kbps
     * and 2 Mbps).
     */
    public static OptionalInt resolve(byte[] bytes, String contentType, Integer hint) {
        OptionalInt parsed = parse(bytes, contentType);
        if (parsed.isPresent()) return parsed;
        if (hint == null || hint <= 0 || bytes == null) return OptionalInt.empty();
        double bitsPerSecond = (double) bytes.length * 8 / hint;
        return bitsPerSecond >= 4_000 && bitsPerSecond <= 2_000_000 ? OptionalInt.of(hint) : OptionalInt.empty();
    }

    /** {@code m:ss} for the composer line. */
    public static String clock(int seconds) {
        int s = Math.max(0, seconds);
        return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    /* ------------------------------- OGG ---------------------------------- */

    private static double ogg(byte[] b) {
        // First page: codec identification. Opus granules are always at 48 kHz;
        // Vorbis carries its rate in the identification header.
        double rate = 48_000;
        long preSkip = 0;
        int first = 0;
        int segments = b[first + 26] & 0xFF;
        int headerEnd = first + 27 + segments;
        if (headerEnd + 19 <= b.length && startsWith(b, headerEnd, "OpusHead")) {
            preSkip = le16(b, headerEnd + 10);
        } else if (headerEnd + 16 <= b.length && startsWith(b, headerEnd + 1, "vorbis")) {
            rate = le32(b, headerEnd + 12);
        }
        // Last page: its granule position is the total samples.
        int last = lastIndexOf(b, "OggS");
        if (last < 0 || last + 14 > b.length) return -1;
        long granule = le64(b, last + 6);
        if (granule <= 0) return -1;
        return (granule - preSkip) / rate;
    }

    /* ------------------------------- WebM --------------------------------- */

    private static final long ID_EBML = 0x1A45DFA3L;
    private static final long ID_SEGMENT = 0x18538067L;
    private static final long ID_INFO = 0x1549A966L;
    private static final long ID_TIMECODE_SCALE = 0x2AD7B1L;
    private static final long ID_DURATION = 0x4489L;

    private static double webm(byte[] b) {
        int pos = 0;
        while (pos < b.length) {
            long[] id = vint(b, pos, true);
            if (id == null) return -1;
            long[] size = vint(b, (int) id[1], false);
            if (size == null) return -1;
            int dataStart = (int) size[1];
            long dataLen = size[0] < 0 ? b.length - dataStart : size[0];
            if (id[0] == ID_SEGMENT) {
                return webmInfo(b, dataStart, (int) Math.min(b.length, dataStart + dataLen));
            }
            pos = (int) Math.min(b.length, dataStart + dataLen);
        }
        return -1;
    }

    private static double webmInfo(byte[] b, int from, int to) {
        int pos = from;
        while (pos < to) {
            long[] id = vint(b, pos, true);
            if (id == null) return -1;
            long[] size = vint(b, (int) id[1], false);
            if (size == null) return -1;
            int dataStart = (int) size[1];
            long dataLen = size[0] < 0 ? to - dataStart : size[0];
            if (id[0] == ID_INFO) {
                double scale = 1_000_000;
                double duration = -1;
                int p = dataStart;
                int end = (int) Math.min(to, dataStart + dataLen);
                while (p < end) {
                    long[] cid = vint(b, p, true);
                    if (cid == null) break;
                    long[] csize = vint(b, (int) cid[1], false);
                    if (csize == null || csize[0] < 0) break;
                    int cs = (int) csize[1];
                    int cl = (int) csize[0];
                    if (cid[0] == ID_TIMECODE_SCALE) scale = uint(b, cs, cl);
                    if (cid[0] == ID_DURATION) duration = cl == 4 ? beFloat(b, cs) : cl == 8 ? beDouble(b, cs) : -1;
                    p = cs + cl;
                }
                return duration <= 0 ? -1 : duration * scale / 1_000_000_000d;
            }
            pos = (int) Math.min(to, dataStart + dataLen);
        }
        return -1;
    }

    /** EBML variable-size integer: {value, nextOffset}; -1 value = unknown size. */
    private static long[] vint(byte[] b, int pos, boolean keepMarker) {
        if (pos >= b.length) return null;
        int first = b[pos] & 0xFF;
        if (first == 0) return null;
        int len = Integer.numberOfLeadingZeros(first) - 23;
        if (pos + len > b.length) return null;
        long value = keepMarker ? first : (first & (0xFF >> len));
        boolean allOnes = !keepMarker && (first & (0xFF >> len)) == (0xFF >> len);
        for (int i = 1; i < len; i++) {
            int v = b[pos + i] & 0xFF;
            value = (value << 8) | v;
            if (v != 0xFF) allOnes = false;
        }
        return new long[]{allOnes ? -1 : value, pos + len};
    }

    /* ------------------------------- MP4 ---------------------------------- */

    private static double mp4(byte[] b) {
        return mp4Boxes(b, 0, b.length);
    }

    private static double mp4Boxes(byte[] b, int from, int to) {
        int pos = from;
        while (pos + 8 <= to) {
            long size = be32(b, pos);
            String type = new String(b, pos + 4, 4, StandardCharsets.ISO_8859_1);
            int header = 8;
            if (size == 1) {
                if (pos + 16 > to) return -1;
                size = be64(b, pos + 8);
                header = 16;
            } else if (size == 0) {
                size = to - pos;
            }
            if (size < header) return -1;
            int end = (int) Math.min(to, pos + size);
            if (type.equals("moov")) {
                return mp4Boxes(b, pos + header, end);
            }
            if (type.equals("mvhd")) {
                int version = b[pos + header] & 0xFF;
                if (version == 1) {
                    double timescale = be32(b, pos + header + 20);
                    double duration = be64(b, pos + header + 24);
                    return timescale <= 0 ? -1 : duration / timescale;
                }
                double timescale = be32(b, pos + header + 12);
                double duration = be32(b, pos + header + 16);
                return timescale <= 0 ? -1 : duration / timescale;
            }
            pos = end;
        }
        return -1;
    }

    /* ------------------------------- WAV ---------------------------------- */

    private static double wav(byte[] b) {
        int pos = 12;
        long byteRate = 0;
        long dataSize = -1;
        while (pos + 8 <= b.length) {
            String id = new String(b, pos, 4, StandardCharsets.ISO_8859_1);
            long size = le32(b, pos + 4);
            if (id.equals("fmt ") && pos + 20 <= b.length) byteRate = le32(b, pos + 16);
            if (id.equals("data")) {
                dataSize = Math.min(size, b.length - pos - 8);
                break;
            }
            pos += 8 + (int) size + (size % 2);
        }
        return byteRate <= 0 || dataSize <= 0 ? -1 : (double) dataSize / byteRate;
    }

    /* ------------------------------- MP3 ---------------------------------- */

    private static final int[][] BITRATES_V1 = {
            {}, // reserved
            {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448}, // layer I
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384},     // layer II
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320}};     // layer III
    private static final int[][] BITRATES_V2 = {
            {},
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}};
    private static final int[] RATES_V1 = {44_100, 48_000, 32_000};
    private static final int[] RATES_V2 = {22_050, 24_000, 16_000};
    private static final int[] RATES_V25 = {11_025, 12_000, 8_000};

    private static double mp3(byte[] b) {
        int pos = 0;
        if (startsWith(b, 0, "ID3") && b.length > 10) {
            int size = ((b[6] & 0x7F) << 21) | ((b[7] & 0x7F) << 14) | ((b[8] & 0x7F) << 7) | (b[9] & 0x7F);
            pos = 10 + size;
        }
        while (pos + 4 <= b.length && !isMp3Sync(b, pos)) pos++;
        if (pos + 4 > b.length) return -1;
        int h1 = b[pos + 1] & 0xFF;
        int h2 = b[pos + 2] & 0xFF;
        int h3 = b[pos + 3] & 0xFF;
        int versionBits = (h1 >> 3) & 0x3;      // 0 = 2.5, 2 = 2, 3 = 1
        int layer = 4 - ((h1 >> 1) & 0x3);      // 1..3
        int bitrateIdx = (h2 >> 4) & 0xF;
        int rateIdx = (h2 >> 2) & 0x3;
        int channelMode = (h3 >> 6) & 0x3;
        if (versionBits == 1 || layer == 4 || bitrateIdx == 0 || bitrateIdx == 15 || rateIdx == 3) return -1;
        boolean v1 = versionBits == 3;
        int bitrate = (v1 ? BITRATES_V1 : BITRATES_V2)[layer][bitrateIdx] * 1_000;
        int sampleRate = (versionBits == 3 ? RATES_V1 : versionBits == 2 ? RATES_V2 : RATES_V25)[rateIdx];
        int samplesPerFrame = layer == 1 ? 384 : layer == 2 ? 1_152 : v1 ? 1_152 : 576;
        // Xing / Info header (VBR): frame count × samples per frame.
        int sideInfo = v1 ? (channelMode == 3 ? 17 : 32) : (channelMode == 3 ? 9 : 17);
        int xing = pos + 4 + sideInfo;
        if (xing + 16 <= b.length && (startsWith(b, xing, "Xing") || startsWith(b, xing, "Info"))) {
            long flags = be32(b, xing + 4);
            if ((flags & 0x1) != 0) {
                long frames = be32(b, xing + 8);
                if (frames > 0) return frames * (double) samplesPerFrame / sampleRate;
            }
        }
        if (bitrate <= 0) return -1;
        return (double) (b.length - pos) * 8 / bitrate;
    }

    private static boolean isMp3Sync(byte[] b, int pos) {
        return pos + 1 < b.length && (b[pos] & 0xFF) == 0xFF && (b[pos + 1] & 0xE0) == 0xE0;
    }

    /* ------------------------------ bytes --------------------------------- */

    private static boolean startsWith(byte[] b, int at, String ascii) {
        if (at < 0 || at + ascii.length() > b.length) return false;
        for (int i = 0; i < ascii.length(); i++) {
            if (b[at + i] != (byte) ascii.charAt(i)) return false;
        }
        return true;
    }

    private static int lastIndexOf(byte[] b, String ascii) {
        for (int i = b.length - ascii.length(); i >= 0; i--) {
            if (startsWith(b, i, ascii)) return i;
        }
        return -1;
    }

    private static long le16(byte[] b, int at) {
        return ByteBuffer.wrap(b, at, 2).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFFL;
    }

    private static long le32(byte[] b, int at) {
        return ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
    }

    private static long le64(byte[] b, int at) {
        return ByteBuffer.wrap(b, at, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static long be32(byte[] b, int at) {
        return ByteBuffer.wrap(b, at, 4).order(ByteOrder.BIG_ENDIAN).getInt() & 0xFFFFFFFFL;
    }

    private static long be64(byte[] b, int at) {
        return ByteBuffer.wrap(b, at, 8).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    private static double beFloat(byte[] b, int at) {
        return ByteBuffer.wrap(b, at, 4).order(ByteOrder.BIG_ENDIAN).getFloat();
    }

    private static double beDouble(byte[] b, int at) {
        return ByteBuffer.wrap(b, at, 8).order(ByteOrder.BIG_ENDIAN).getDouble();
    }

    private static long uint(byte[] b, int at, int len) {
        long v = 0;
        for (int i = 0; i < len && at + i < b.length; i++) v = (v << 8) | (b[at + i] & 0xFF);
        return v;
    }
}
