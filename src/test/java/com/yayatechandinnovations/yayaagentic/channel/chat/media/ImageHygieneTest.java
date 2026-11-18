package com.yayatechandinnovations.yayaagentic.channel.chat.media;

import com.yayatechandinnovations.yayaagentic.testsupport.TestMedia;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Server-side image hygiene (design §3.6): EXIF gone, orientation kept, long side capped, WebP untouched. */
class ImageHygieneTest {

    @Test
    void reencodingStripsExifAndAppliesTheOrientation() throws Exception {
        byte[] withExif = TestMedia.jpegWithExifOrientation(40, 20, 6);
        assertThat(ImageHygiene.hasExif(withExif)).isTrue();
        assertThat(ImageHygiene.exifOrientation(withExif)).isEqualTo(6);

        ImageHygiene.Normalized out = ImageHygiene.normalize(withExif, "image/jpeg");

        assertThat(out.reencoded()).isTrue();
        assertThat(out.contentType()).isEqualTo("image/jpeg");
        assertThat(ImageHygiene.hasExif(out.bytes())).isFalse();
        assertThat(ImageHygiene.exifOrientation(out.bytes())).isEqualTo(1);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(out.bytes()));
        // orientation 6 = rotate 90° clockwise: 40×20 becomes 20×40, red (left) ends up on top
        assertThat(img.getWidth()).isEqualTo(20);
        assertThat(img.getHeight()).isEqualTo(40);
        int top = img.getRGB(10, 5);
        int bottom = img.getRGB(10, 35);
        assertThat((top >> 16) & 0xFF).isGreaterThan(150);      // red channel on top
        assertThat((bottom >> 8) & 0xFF).isGreaterThan(150);    // green channel at the bottom
    }

    @Test
    void aLongSideOverTheCapIsScaledDown() throws Exception {
        ImageHygiene.Normalized out = ImageHygiene.normalize(TestMedia.png(3_200, 400), "image/png");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(out.bytes()));
        assertThat(img.getWidth()).isEqualTo(ImageHygiene.MAX_LONG_SIDE);
        assertThat(img.getHeight()).isEqualTo(200);
        assertThat(out.contentType()).isEqualTo("image/png");
    }

    @Test
    void webpAndUnreadableBytesPassThroughUnchanged() {
        byte[] webp = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
        ImageHygiene.Normalized same = ImageHygiene.normalize(webp, "image/webp");
        assertThat(same.reencoded()).isFalse();
        assertThat(same.bytes()).isSameAs(webp);

        byte[] broken = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3};
        assertThat(ImageHygiene.normalize(broken, "image/jpeg").bytes()).isSameAs(broken);
    }

    @Test
    void heicIsRecognisedByType() {
        assertThat(ImageHygiene.isHeic("image/heic")).isTrue();
        assertThat(ImageHygiene.isHeic("image/heif; charset=binary")).isTrue();
        assertThat(ImageHygiene.isHeic("image/jpeg")).isFalse();
    }
}
