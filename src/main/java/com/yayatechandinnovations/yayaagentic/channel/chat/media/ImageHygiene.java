package com.yayatechandinnovations.yayaagentic.channel.chat.media;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;

/**
 * Server-side image hygiene (assistant-channels design §3.6): HEIC/HEIF is
 * refused (the JVM cannot decode it and the model does not take it); JPEG
 * and PNG are re-encoded, which drops EXIF (location, device) — the EXIF
 * orientation is applied first so the picture is not left on its side — and
 * a long side over {@value #MAX_LONG_SIDE} px is scaled down (bandwidth and
 * vision tokens). WebP passes through unchanged.
 *
 * <p>Before anything is decoded the header is read alone: a picture declaring
 * more than {@value #MAX_PIXELS} pixels or a side over {@value #MAX_SIDE} px
 * is refused ({@link ImageTooLargeException}) — a few hundred bytes of PNG can
 * declare a 30,000 × 30,000 canvas that would take gigabytes to decode.
 */
public final class ImageHygiene {

    private static final Logger log = LoggerFactory.getLogger(ImageHygiene.class);

    public static final int MAX_LONG_SIDE = 1_600;
    public static final float JPEG_QUALITY = 0.85f;
    /** Decode ceiling: 40 megapixels, and no side over 12,000 px. */
    public static final long MAX_PIXELS = 40_000_000L;
    public static final int MAX_SIDE = 12_000;
    /** What the person is told when the photo is an iPhone original. */
    public static final String HEIC_HINT = "Mándala como JPG o PNG";
    /** What the person is told when the picture declares more than the decode ceiling. */
    public static final String TOO_BIG_HINT = "La imagen es demasiado grande (máximo 40 MP)";

    private ImageHygiene() {}

    /** The picture's header declares more than {@link #MAX_PIXELS} or a side over {@link #MAX_SIDE}. */
    public static final class ImageTooLargeException extends RuntimeException {
        private final int width;
        private final int height;

        public ImageTooLargeException(int width, int height) {
            super("image declares " + width + "x" + height + " px; the limit is " + MAX_PIXELS + " px");
            this.width = width;
            this.height = height;
        }

        public int width() { return width; }

        public int height() { return height; }
    }

    /**
     * The bytes to store or send, their type, and — when they were decoded —
     * the picture itself, so the code reader does not decode them a second time.
     */
    public record Normalized(byte[] bytes, String contentType, boolean reencoded, BufferedImage image) {
        public Normalized(byte[] bytes, String contentType, boolean reencoded) {
            this(bytes, contentType, reencoded, null);
        }
    }

    /** What the header declares, before any pixel is decoded. */
    public record Dimensions(int width, int height) {
        public boolean withinLimits() {
            return width > 0 && height > 0 && width <= MAX_SIDE && height <= MAX_SIDE
                    && (long) width * height <= MAX_PIXELS;
        }
    }

    /** The declared size from the header alone, or empty when no reader knows the format. */
    public static Optional<Dimensions> declaredSize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return Optional.empty();
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) return Optional.empty();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return Optional.empty();
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return Optional.of(new Dimensions(reader.getWidth(0), reader.getHeight(0)));
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Refuses, from the header alone, a picture over the decode ceiling. A
     * format no reader knows passes (nothing here will decode it).
     */
    public static void requireWithinLimits(byte[] bytes) {
        declaredSize(bytes).filter(d -> !d.withinLimits()).ifPresent(d -> {
            throw new ImageTooLargeException(d.width(), d.height());
        });
    }

    public static boolean isHeic(String contentType) {
        if (contentType == null) return false;
        String ct = bare(contentType);
        return ct.equals("image/heic") || ct.equals("image/heif")
                || ct.equals("image/heic-sequence") || ct.equals("image/heif-sequence");
    }

    /**
     * Re-encodes JPEG/PNG (orientation applied, EXIF gone, long side capped);
     * anything else is returned as is. The only exception out of here is
     * {@link ImageTooLargeException}, raised from the header before any
     * decoding; otherwise a picture ImageIO cannot read (CMYK JPEG, a damaged
     * file) goes through untouched, with a log line.
     */
    public static Normalized normalize(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) return new Normalized(bytes, contentType, false);
        requireWithinLimits(bytes);
        String ct = bare(contentType);
        boolean jpeg = ct.equals("image/jpeg") || ct.equals("image/jpg");
        boolean png = ct.equals("image/png");
        if (!(jpeg || png)) {
            return new Normalized(bytes, contentType, false);
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) return new Normalized(bytes, contentType, false);
            if (jpeg) img = applyOrientation(img, exifOrientation(bytes));
            img = capLongSide(img, MAX_LONG_SIDE);
            byte[] out = jpeg ? writeJpeg(img) : writePng(img);
            return new Normalized(out, jpeg ? "image/jpeg" : "image/png", true, img);
        } catch (Exception e) {
            log.info("image re-encode skipped ({}): {}", ct, e.toString());
            return new Normalized(bytes, contentType, false);
        }
    }

    /** EXIF orientation 1–8 of a JPEG; 1 when absent or unreadable. */
    public static int exifOrientation(byte[] jpeg) {
        try {
            Metadata md = ImageMetadataReader.readMetadata(new ByteArrayInputStream(jpeg));
            ExifIFD0Directory dir = md.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (dir == null || !dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) return 1;
            int o = dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            return o >= 1 && o <= 8 ? o : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    /** True when the JPEG carries an APP1 Exif segment (what the re-encode removes). */
    public static boolean hasExif(byte[] jpeg) {
        if (jpeg == null || jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) return false;
        int i = 2;
        while (i + 4 <= jpeg.length && (jpeg[i] & 0xFF) == 0xFF) {
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) break; // start of scan / end
            int len = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if (marker == 0xE1 && i + 10 <= jpeg.length
                    && jpeg[i + 4] == 'E' && jpeg[i + 5] == 'x' && jpeg[i + 6] == 'i' && jpeg[i + 7] == 'f') {
                return true;
            }
            if (len < 2) break;
            i += 2 + len;
        }
        return false;
    }

    static BufferedImage applyOrientation(BufferedImage img, int orientation) {
        if (orientation <= 1 || orientation > 8) return img;
        int w = img.getWidth();
        int h = img.getHeight();
        AffineTransform t = new AffineTransform();
        boolean swap = orientation >= 5;
        switch (orientation) {
            case 2 -> { t.scale(-1, 1); t.translate(-w, 0); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.scale(1, -1); t.translate(0, -h); }
            case 5 -> { t.rotate(-Math.PI / 2); t.scale(-1, 1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { }
        }
        BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, rgbType(img));
        AffineTransformOp op = new AffineTransformOp(t, AffineTransformOp.TYPE_BILINEAR);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(img, op, 0, 0);
        } finally {
            g.dispose();
        }
        return out;
    }

    static BufferedImage capLongSide(BufferedImage img, int max) {
        int w = img.getWidth();
        int h = img.getHeight();
        int longSide = Math.max(w, h);
        if (longSide <= max) return img;
        double f = (double) max / longSide;
        int nw = Math.max(1, (int) Math.round(w * f));
        int nh = Math.max(1, (int) Math.round(h * f));
        BufferedImage out = new BufferedImage(nw, nh, rgbType(img));
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(img, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static int rgbType(BufferedImage img) {
        return img.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    }

    private static byte[] writeJpeg(BufferedImage img) throws Exception {
        BufferedImage rgb = img;
        if (img.getColorModel().hasAlpha() || img.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, img.getWidth(), img.getHeight());
                g.drawImage(img, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            // No metadata object: the writer emits a bare JFIF stream, so EXIF is gone.
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    private static byte[] writePng(BufferedImage img) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private static String bare(String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        int semi = ct.indexOf(';');
        return (semi >= 0 ? ct.substring(0, semi) : ct).trim();
    }
}
