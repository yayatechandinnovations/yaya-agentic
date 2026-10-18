package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inbound WhatsApp photos: fetched with the sender's Basic auth, capped per
 * message and per image, exposed to the engine as {@code chat.images} inline
 * parts and to tools as {@code images} URL metadata.
 */
class InboundImageLoaderTest {

    private WireMockServer cdn;

    record Media(String mediaUrl, String contentType, long sizeBytes) implements InboundImageLoader.MediaRef {}

    @BeforeEach
    void start() {
        cdn = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        cdn.start();
    }

    @AfterEach
    void stop() {
        cdn.stop();
    }

    private String url(String path) {
        return "http://localhost:" + cdn.port() + path;
    }

    @Test
    void loads_images_with_basic_auth_and_skips_non_images() {
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        cdn.stubFor(get(urlEqualTo("/m/1"))
                .withHeader("Authorization", equalTo("Basic dXNlcjpwYXNz"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "image/jpeg").withBody(jpeg)));

        InboundImageLoader loader = new InboundImageLoader(WebClient.builder(), 4, 5L * 1024 * 1024);
        List<InboundImageLoader.LoadedImage> images = loader.load(List.of(
                new Media(url("/m/1"), "image/jpeg", 0),
                new Media(url("/m/audio"), "audio/ogg; codecs=opus", 0)), "Basic dXNlcjpwYXNz");

        assertThat(images).hasSize(1);
        InboundImageLoader.LoadedImage img = images.get(0);
        assertThat(img.contentType()).isEqualTo("image/jpeg");
        assertThat(img.sizeBytes()).isEqualTo(jpeg.length);
        assertThat(Base64.getDecoder().decode(img.base64())).isEqualTo(jpeg);

        // the engine's vision input and the tool-facing URL metadata
        Map<String, Object> attachments = InboundDispatchWorker.imageAttachments(images);
        assertThat(attachments).containsKeys("chat.images", "images");
        @SuppressWarnings("unchecked")
        Map<String, Object> inline = ((List<Map<String, Object>>) attachments.get("chat.images")).get(0);
        assertThat(inline).containsEntry("contentType", "image/jpeg").containsKey("base64");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = ((List<Map<String, Object>>) attachments.get("images")).get(0);
        assertThat(meta).containsEntry("url", url("/m/1")).containsEntry("contentType", "image/jpeg")
                .containsEntry("sizeBytes", (long) jpeg.length);
        assertThat(meta).doesNotContainKey("base64");
    }

    @Test
    void caps_count_and_size_and_survives_cdn_errors() {
        byte[] small = new byte[16];
        byte[] big = new byte[64];
        for (int i = 1; i <= 5; i++) {
            cdn.stubFor(get(urlEqualTo("/m/" + i)).willReturn(aResponse().withStatus(200).withBody(small)));
        }
        cdn.stubFor(get(urlEqualTo("/m/big")).willReturn(aResponse().withStatus(200).withBody(big)));
        cdn.stubFor(get(urlEqualTo("/m/404")).willReturn(aResponse().withStatus(404)));

        InboundImageLoader loader = new InboundImageLoader(WebClient.builder(), 2, 32);
        List<InboundImageLoader.LoadedImage> images = loader.load(List.of(
                new Media(url("/m/404"), "image/png", 0),
                new Media(url("/m/big"), "image/png", 0),
                new Media(url("/m/1"), "image/png", 0),
                new Media(url("/m/2"), "image/png", 0),
                new Media(url("/m/3"), "image/png", 0)), null);

        assertThat(images).extracting(InboundImageLoader.LoadedImage::url)
                .containsExactly(url("/m/1"), url("/m/2"));
        assertThat(InboundDispatchWorker.imageAttachments(List.of())).isEmpty();
    }
}
