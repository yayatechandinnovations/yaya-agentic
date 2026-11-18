package com.yayatechandinnovations.yayaagentic.channel.chat.media;

import com.yayatechandinnovations.yayaagentic.testsupport.TestMedia;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(ImageCodes.decode(null)).isEmpty();
    }

    @Test
    void codeMapIsWhatTheAttachmentStores() {
        assertThat(new ImageCodes.Code("QR_CODE", "abc").toMap())
                .containsEntry("format", "QR_CODE").containsEntry("text", "abc");
    }
}
