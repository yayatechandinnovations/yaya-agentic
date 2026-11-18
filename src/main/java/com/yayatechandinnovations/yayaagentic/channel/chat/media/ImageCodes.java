package com.yayatechandinnovations.yayaagentic.channel.chat.media;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Deterministic QR / 1-D barcode reading (assistant-channels design §3.4):
 * every image that reaches the model is tried first at its own size, then at
 * half size (a 2× downscale helps on large phone photos where the finder
 * patterns are too big for ZXing's sampling), inside a 300 ms budget. The
 * result is data about the image, never an instruction — the composer puts
 * it inside the untrusted-data delimiters.
 */
public final class ImageCodes {

    private static final Logger log = LoggerFactory.getLogger(ImageCodes.class);
    public static final long BUDGET_MS = 300;

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "image-codes");
        t.setDaemon(true);
        return t;
    });

    private static final Map<DecodeHintType, Object> HINTS;

    static {
        Map<DecodeHintType, Object> h = new EnumMap<>(DecodeHintType.class);
        h.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        h.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(
                BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC,
                BarcodeFormat.PDF_417, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
                BarcodeFormat.CODE_93, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.ITF,
                BarcodeFormat.CODABAR));
        HINTS = Map.copyOf(h);
        // The first decode pays for ZXing's and ImageIO's class loading (~200 ms);
        // take it at startup, off the request path, so the budget is real.
        Thread warm = new Thread(() -> {
            try {
                BufferedImage blank = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
                attempt(blank);
            } catch (Throwable ignored) {
                // warm-up only
            }
        }, "image-codes-warmup");
        warm.setDaemon(true);
        warm.start();
    }

    private ImageCodes() {}

    /** One decoded code; {@code format} is the ZXing name ({@code QR_CODE}, {@code CODE_128}…). */
    public record Code(String format, String text) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("format", format);
            m.put("text", text);
            return m;
        }
    }

    /**
     * Never throws; empty when nothing was read. The budget covers the ZXing
     * passes (the image decode itself is bounded by the upload caps); the wait
     * here allows for it plus the decode.
     */
    public static List<Code> decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return List.of();
        Future<List<Code>> f = POOL.submit(() -> decodeWithin(bytes, 0));
        try {
            return f.get(BUDGET_MS * 4, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            log.debug("code reading gave up after {} ms", BUDGET_MS);
            return List.of();
        } catch (Exception e) {
            log.debug("code reading failed: {}", e.toString());
            return List.of();
        }
    }

    /** {@code deadline} in {@link System#nanoTime()} units; 0 = the budget from after the image decode. */
    static List<Code> decodeWithin(byte[] bytes, long deadline) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return List.of();
        }
        if (img == null) return List.of();
        if (deadline == 0) deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BUDGET_MS);
        // A phone photo is big: the half-size pass is cheap and usually enough,
        // so it goes first there; a small image is tried as is first.
        boolean large = img.getWidth() >= 800 && img.getHeight() >= 800;
        BufferedImage first = large ? halve(img) : img;
        List<Code> found = attempt(first);
        if (!found.isEmpty() || System.nanoTime() >= deadline) return found;
        if (large) return attempt(img);
        if (img.getWidth() >= 400 && img.getHeight() >= 400) return attempt(halve(img));
        return found;
    }

    private static List<Code> attempt(BufferedImage img) {
        LuminanceSource source = new BufferedImageLuminanceSource(img);
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        MultiFormatReader reader = new MultiFormatReader();
        reader.setHints(HINTS);
        Set<String> seen = new LinkedHashSet<>();
        List<Code> out = new ArrayList<>();
        try {
            Result[] results = new GenericMultipleBarcodeReader(reader).decodeMultiple(bitmap, HINTS);
            for (Result r : results) add(out, seen, r);
        } catch (NotFoundException none) {
            try {
                add(out, seen, reader.decodeWithState(bitmap));
            } catch (NotFoundException ignored) {
                // nothing in this image
            }
        } catch (RuntimeException e) {
            log.debug("zxing failed: {}", e.toString());
        }
        return out;
    }

    private static void add(List<Code> out, Set<String> seen, Result r) {
        if (r == null || r.getText() == null || r.getText().isBlank()) return;
        String format = r.getBarcodeFormat() == null ? "UNKNOWN" : r.getBarcodeFormat().name();
        String text = r.getText().trim();
        if (text.length() > 2_000) text = text.substring(0, 2_000);
        if (seen.add(format + "|" + text)) out.add(new Code(format, text));
    }

    private static BufferedImage halve(BufferedImage img) {
        int w = Math.max(1, img.getWidth() / 2);
        int h = Math.max(1, img.getHeight() / 2);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
