package com.yayatechandinnovations.yayaagentic.channel.chat.media;

import com.yayatechandinnovations.yayaagentic.testsupport.TestMedia;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** ZXing reading on uploads and inbound photos (assistant-channels design §3.4). */
class ImageCodesTest {

    @Test
    void readsAQrCodeFromAGeneratedImage() {
        List<ImageCodes.Code> codes = ImageCodes.decode(TestMedia.qrPng("4130552218-7781902345", 300));
        assertThat(codes).hasSize(1);
        assertThat(codes.get(0).format()).isEqualTo("QR_CODE");
        assertThat(codes.get(0).text()).isEqualTo("4130552218-7781902345");
    }

    @Test
    void readsALargePhotoSizedQrThroughTheDownscale() {
        List<ImageCodes.Code> codes = ImageCodes.decode(TestMedia.qrPng("https://x.example/t/4411", 1400));
        assertThat(codes).extracting(ImageCodes.Code::text).containsExactly("https://x.example/t/4411");
    }

    @Test
    void nothingToReadIsEmptyAndNeverThrows() {
        assertThat(ImageCodes.decode(TestMedia.png(320, 200))).isEmpty();
        assertThat(ImageCodes.decode(new byte[]{1, 2, 3})).isEmpty();
        assertThat(ImageCodes.decode((byte[]) null)).isEmpty();
    }

    @Test
    void readsFromAnAlreadyDecodedPicture() throws Exception {
        var img = ImageIO.read(new ByteArrayInputStream(TestMedia.qrPng("4130552218-7781902345", 300)));
        assertThat(ImageCodes.decode(img)).extracting(ImageCodes.Code::text).containsExactly("4130552218-7781902345");
        assertThat(ImageCodes.decode((java.awt.image.BufferedImage) null)).isEmpty();
    }

    @Test
    void aPictureOverTheDecodeCeilingIsNotDecoded() {
        byte[] bomb = TestMedia.pngDeclaring(30_000, 30_000);
        assertThat(assertTimeoutPreemptively(Duration.ofSeconds(2), () -> ImageCodes.decode(bomb))).isEmpty();
    }

    @Test
    void whenBothReaderThreadsAreBusyTheImageGetsNoCodesAtOnce() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(ImageCodes.THREADS);
        List<Future<List<ImageCodes.Code>>> busy = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < ImageCodes.THREADS; i++) {
                busy.add(ImageCodes.submit(() -> {
                    running.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return List.of();
                }));
            }
            assertThat(busy).doesNotContainNull();
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();

            byte[] qr = TestMedia.qrPng("4130552218-7781902345", 300);
            long t0 = System.nanoTime();
            List<ImageCodes.Code> codes = ImageCodes.decode(qr);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            // No third thread, no queue slot: skipped instead of waiting out the budget.
            assertThat(codes).isEmpty();
            assertThat(elapsedMs).isLessThan(ImageCodes.BUDGET_MS);
        } finally {
            release.countDown();
        }
        for (Future<List<ImageCodes.Code>> f : busy) f.get(5, TimeUnit.SECONDS);
        // The pool recovers once the slow passes finish.
        assertThat(ImageCodes.decode(TestMedia.qrPng("4130552218-7781902345", 300))).hasSize(1);
    }

    @Test
    void codeMapIsWhatTheAttachmentStores() {
        assertThat(new ImageCodes.Code("QR_CODE", "abc").toMap())
                .containsEntry("format", "QR_CODE").containsEntry("text", "abc");
    }
}
