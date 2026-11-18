package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Nothing arriving on WhatsApp is silently dropped (design §3.3). */
class InboundMediaGateTest {

    private static InboundDispatchWorker.MediaDescriptor m(String type) {
        return new InboundDispatchWorker.MediaDescriptor("https://cdn/x", type, 0);
    }

    @Test
    void sortsWhatTheAssistantTakesFromWhatGetsAFixedReply() {
        InboundMediaGate.Plan plan = InboundMediaGate.classify(List.of(
                m("image/jpeg"), m("application/pdf"), m("audio/ogg; codecs=opus"),
                m("video/mp4"), m("image/webp"), m("text/vcard"), m("image/heic")), Map.of(), true, true);
        assertThat(plan.images()).hasSize(1);
        assertThat(plan.documents()).hasSize(1);
        assertThat(plan.audio()).hasSize(1);
        assertThat(plan.fixedReplies()).containsExactly(InboundMediaGate.UNSUPPORTED_REPLY, InboundMediaGate.HEIC_REPLY);
    }

    @Test
    void aVoiceNoteWithoutATranscriberOrWithoutMinutesGetsItsOwnReply() {
        assertThat(InboundMediaGate.classify(List.of(m("audio/ogg")), Map.of(), false, true).fixedReplies())
                .containsExactly(InboundMediaGate.VOICE_UNAVAILABLE_REPLY);
        assertThat(InboundMediaGate.classify(List.of(m("audio/ogg")), Map.of(), true, false).fixedReplies())
                .containsExactly(InboundMediaGate.VOICE_QUOTA_REPLY);
        assertThat(InboundMediaGate.classify(List.of(m("audio/ogg")), Map.of(), true, true).audio()).hasSize(1);
    }

    @Test
    void aSharedLocationIsUnsupportedToo() {
        InboundMediaGate.Plan plan = InboundMediaGate.classify(List.of(),
                Map.of("Latitude", "18.47", "Longitude", "-69.9"), true, true);
        assertThat(plan.hasMedia()).isFalse();
        assertThat(plan.fixedReplies()).containsExactly(InboundMediaGate.UNSUPPORTED_REPLY);
    }

    @Test
    void mediaOnlyTextNamesWhatArrived() {
        InboundImageLoader.LoadedImage photo = new InboundImageLoader.LoadedImage("u", "image/jpeg", 10, "AA==",
                List.of(new com.yayatechandinnovations.yayaagentic.channel.chat.media.ImageCodes.Code("QR_CODE", "4130552218-7781902345")));
        InboundDispatchWorker.InboundDocument doc = new InboundDispatchWorker.InboundDocument("u2", "factura.pdf",
                "application/pdf", new byte[]{1});
        String text = InboundDispatchWorker.mediaText("", List.of(photo), List.of(doc));
        assertThat(text).startsWith("(foto)\n(documento: factura.pdf)")
                .contains("Código leído en la imagen: 4130552218-7781902345")
                .contains("<<<attachment-content");
        // with text, only the codes are added
        assertThat(InboundDispatchWorker.mediaText("hola", List.of(photo), List.of()))
                .doesNotContain("(foto)").contains("Código leído en la imagen");
        assertThat(InboundDispatchWorker.mediaText("hola", List.of(), List.of())).isEmpty();
    }
}
