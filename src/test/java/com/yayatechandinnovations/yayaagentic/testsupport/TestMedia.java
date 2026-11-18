package com.yayatechandinnovations.yayaagentic.testsupport;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/** Hand-made media for the attachment tests: real bytes, no fixtures on disk. */
public final class TestMedia {

    private TestMedia() {}

    /** A PNG holding a QR code with {@code text}, {@code size} px square. */
    public static byte[] qrPng(String text, int size) {
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A plain PNG of the given size (solid colour with a diagonal, so it is not trivially blank). */
    public static byte[] png(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLUE);
        g.drawLine(0, 0, width - 1, height - 1);
        g.dispose();
        return write(img, "png");
    }

    /**
     * A few dozen bytes of PNG whose IHDR declares {@code width × height}: what
     * a decompression bomb looks like before anything is decoded.
     */
    public static byte[] pngDeclaring(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(17).order(ByteOrder.BIG_ENDIAN);
        ihdr.put("IHDR".getBytes(StandardCharsets.ISO_8859_1)).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(ihdr.array());
        ByteBuffer b = ByteBuffer.allocate(8 + 4 + 17 + 4 + 12).order(ByteOrder.BIG_ENDIAN);
        b.put(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        b.putInt(13).put(ihdr.array()).putInt((int) crc.getValue());
        b.putInt(0).put("IEND".getBytes(StandardCharsets.ISO_8859_1)).putInt(0xAE426082);
        return b.array();
    }

    /** A PNG of random pixels — incompressible, so its size is roughly 3 bytes per pixel. */
    public static byte[] noisyPng(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(7);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) row[x] = r.nextInt();
            img.setRGB(0, y, width, 1, row, 0, width);
        }
        return write(img, "png");
    }

    /** A JPEG whose left half is red and right half is green, {@code width} × {@code height}. */
    public static byte[] jpeg(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width / 2, height);
        g.setColor(Color.GREEN);
        g.fillRect(width / 2, 0, width - width / 2, height);
        g.dispose();
        return write(img, "jpeg");
    }

    /** The same JPEG with an APP1 Exif segment carrying {@code orientation} (1–8). */
    public static byte[] jpegWithExifOrientation(int width, int height, int orientation) {
        byte[] plain = jpeg(width, height);
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 12 + 4).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        tiff.putShort((short) 1);                           // one IFD0 entry
        tiff.putShort((short) 0x0112).putShort((short) 3);  // Orientation, SHORT
        tiff.putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putInt(0);                                     // no next IFD
        byte[] exif = ("Exif\0\0").getBytes(StandardCharsets.ISO_8859_1);
        int len = 2 + exif.length + tiff.capacity();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2);                             // SOI
        out.write(0xFF);
        out.write(0xE1);
        out.write((len >> 8) & 0xFF);
        out.write(len & 0xFF);
        out.write(exif, 0, exif.length);
        out.write(tiff.array(), 0, tiff.capacity());
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    /** HEIC magic: an ISO container whose brand is {@code heic}. */
    public static byte[] heic() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] ftyp = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0,
                'm', 'i', 'f', '1', 'h', 'e', 'i', 'c'};
        out.write(ftyp, 0, ftyp.length);
        for (int i = 0; i < 64; i++) out.write(0);
        return out.toByteArray();
    }

    /** A PCM WAV (8 kHz, 16-bit mono) of {@code seconds} seconds of silence. */
    public static byte[] wav(int seconds) {
        int byteRate = 16_000;
        int data = byteRate * seconds;
        ByteBuffer b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.ISO_8859_1)).putInt(36 + data)
                .put("WAVE".getBytes(StandardCharsets.ISO_8859_1))
                .put("fmt ".getBytes(StandardCharsets.ISO_8859_1)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(8_000).putInt(byteRate)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.ISO_8859_1)).putInt(data);
        return b.array();
    }

    /** An OGG/Opus stream whose last page says {@code seconds} of audio (pre-skip 312). */
    public static byte[] oggOpus(int seconds) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // page 0: OpusHead
        ByteBuffer head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        head.put("OpusHead".getBytes(StandardCharsets.ISO_8859_1)).put((byte) 1).put((byte) 1)
                .putShort((short) 312).putInt(48_000).putShort((short) 0).put((byte) 0);
        oggPage(out, 2, 0, 1, head.array());
        // page 1: a frame
        oggPage(out, 0, 960, 2, new byte[]{1, 2, 3});
        // last page (EOS)
        oggPage(out, 4, 48_000L * seconds + 312, 3, new byte[]{1});
        return out.toByteArray();
    }

    private static void oggPage(ByteArrayOutputStream out, int type, long granule, int seq, byte[] data) {
        ByteBuffer h = ByteBuffer.allocate(27 + 1).order(ByteOrder.LITTLE_ENDIAN);
        h.put("OggS".getBytes(StandardCharsets.ISO_8859_1)).put((byte) 0).put((byte) type)
                .putLong(granule).putInt(0x1234).putInt(seq).putInt(0).put((byte) 1).put((byte) data.length);
        out.write(h.array(), 0, h.capacity());
        out.write(data, 0, data.length);
    }

    /** An M4A container with only {@code ftyp} and {@code moov/mvhd} (timescale 1000). */
    public static byte[] m4a(int seconds) {
        ByteBuffer b = ByteBuffer.allocate(16 + 8 + 108).order(ByteOrder.BIG_ENDIAN);
        b.putInt(16).put("ftyp".getBytes(StandardCharsets.ISO_8859_1))
                .put("M4A ".getBytes(StandardCharsets.ISO_8859_1)).putInt(0);
        b.putInt(8 + 108).put("moov".getBytes(StandardCharsets.ISO_8859_1));
        b.putInt(108).put("mvhd".getBytes(StandardCharsets.ISO_8859_1));
        b.putInt(0).putInt(0).putInt(0).putInt(1_000).putInt(seconds * 1_000);
        while (b.hasRemaining()) b.put((byte) 0);
        return b.array();
    }

    /** A WebM with an EBML header and a Segment/Info carrying the duration. */
    public static byte[] webm(double seconds) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, 0x1A, 0x45, 0xDF, 0xA3, 0x80);                     // EBML header, empty
        ByteBuffer info = ByteBuffer.allocate(3 + 1 + 3 + 2 + 1 + 4).order(ByteOrder.BIG_ENDIAN);
        info.put((byte) 0x2A).put((byte) 0xD7).put((byte) 0xB1).put((byte) 0x83)
                .put((byte) 0x0F).put((byte) 0x42).put((byte) 0x40);        // TimecodeScale 1,000,000
        info.put((byte) 0x44).put((byte) 0x89).put((byte) 0x84).putFloat((float) (seconds * 1_000)); // Duration
        byte[] infoBytes = info.array();
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        write(segment, 0x15, 0x49, 0xA9, 0x66, 0x80 | infoBytes.length);
        segment.write(infoBytes, 0, infoBytes.length);
        byte[] seg = segment.toByteArray();
        write(out, 0x18, 0x53, 0x80, 0x67, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF); // unknown size
        out.write(seg, 0, seg.length);
        return out.toByteArray();
    }

    /** A one-page PDF with {@code text} in Helvetica, offsets correct. */
    public static byte[] pdf(String text) {
        String content = "BT /F1 24 Tf 20 100 Td (" + text.replace("(", "\\(").replace(")", "\\)") + ") Tj ET";
        String[] objects = {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 144] /Contents 4 0 R "
                        + "/Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + content.length() + " >>\nstream\n" + content + "\nendstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"};
        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        long[] offsets = new long[objects.length + 1];
        for (int i = 0; i < objects.length; i++) {
            offsets[i + 1] = sb.length();
            sb.append(i + 1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n");
        }
        long xref = sb.length();
        sb.append("xref\n0 ").append(objects.length + 1).append("\n0000000000 65535 f \n");
        for (int i = 1; i <= objects.length; i++) {
            sb.append(String.format("%010d 00000 n \n", offsets[i]));
        }
        sb.append("trailer\n<< /Root 1 0 R /Size ").append(objects.length + 1).append(" >>\nstartxref\n")
                .append(xref).append("\n%%EOF\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void write(ByteArrayOutputStream out, int... bytes) {
        for (int b : bytes) out.write(b);
    }

    private static byte[] write(BufferedImage img, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, format, out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
