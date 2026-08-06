package com.yayatechandinnovations.yayaagentic.channel.chat.media;

import com.yayatechandinnovations.yayaagentic.channel.MediaTranscriber;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.BlobStore;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.MalwareScanner;
import com.yayatechandinnovations.yayaagentic.knowledge.extract.ContentTypeSniffer;
import com.yayatechandinnovations.yayaagentic.knowledge.extract.TextExtractor;
import com.yayatechandinnovations.yayaagentic.outreach.media.MediaTokenSigner;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAttachmentEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAttachmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The upload gauntlet ({@code chat-experience-design.md} §6.2): sniffed-type
 * allowlist, per-kind caps, malware verdicts, the daily byte ceiling, and
 * single-use send resolution.
 */
class ChatAttachmentServiceTest {

    // Real PNG magic so the real sniffer classifies without guessing from the name.
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0};

    private final ChatAttachmentRepository repo = mock(ChatAttachmentRepository.class);
    private final BlobStore blobStore = mock(BlobStore.class);
    private final Ids.TenantId tenant = new Ids.TenantId("t1");

    private MalwareScanner.Verdict verdict = MalwareScanner.Verdict.CLEAN;

    @SuppressWarnings("unchecked")
    private ChatAttachmentService service() {
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repo.bytesUploadedSince(any(), any())).thenReturn(0L);
        when(blobStore.store(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new BlobStore.StoredBlob("blob1", "sha", PNG.length, "image/png"));
        ObjectProvider<MediaTranscriber> transcribers = mock(ObjectProvider.class);
        when(transcribers.stream()).thenAnswer(i -> Stream.empty());
        TextExtractor extractor = mock(TextExtractor.class);
        when(extractor.extract(any(InputStream.class), any(), any()))
                .thenReturn(new TextExtractor.Extracted(
                        List.of(new TextExtractor.Page(1, "extracted text")), Map.of()));
        return new ChatAttachmentService(repo, blobStore, (data, name) -> verdict,
                new ContentTypeSniffer(), extractor, new MediaTokenSigner("test-key"),
                transcribers, "http://localhost:8080");
    }

    @Test
    void pngUploadsAsImage() {
        var att = service().upload(tenant, "s1", UUID.randomUUID(), "photo.png", PNG);
        assertThat(att.kind()).isEqualTo("IMAGE");
        assertThat(att.signedUrl()).contains("/v1/chat/media/");
    }

    @Test
    void webmVoiceNoteClassifiesAsAudio() {
        // Browser recordings are Opus in a WebM (EBML) container; Tika sniffs
        // the container as video/webm — it must still land as AUDIO.
        byte[] webm = {(byte) 0x1A, (byte) 0x45, (byte) 0xDF, (byte) 0xA3,
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06};
        var att = service().upload(tenant, "s1", UUID.randomUUID(), "voice-note.webm", webm);
        assertThat(att.kind()).isEqualTo("AUDIO");
    }

    @Test
    void unsupportedTypeIsRejected() {
        byte[] exe = {'M', 'Z', 0, 0, 1, 2, 3};
        assertThatThrownBy(() -> service().upload(tenant, "s1", UUID.randomUUID(), "x.exe", exe))
                .isInstanceOf(ChatAttachmentService.RejectedException.class)
                .hasFieldOrPropertyWithValue("code", "unsupported_type");
    }

    @Test
    void declaredNameNeverBeatsSniffedBytes() {
        // Executable bytes with an innocent name: the sniffed type decides.
        byte[] exe = {'M', 'Z', 0, 0, 1, 2, 3};
        assertThatThrownBy(() -> service().upload(tenant, "s1", UUID.randomUUID(), "cute.png", exe))
                .isInstanceOf(ChatAttachmentService.RejectedException.class);
    }

    @Test
    void infectedFileIsRejected() {
        verdict = MalwareScanner.Verdict.INFECTED;
        assertThatThrownBy(() -> service().upload(tenant, "s1", UUID.randomUUID(), "p.png", PNG))
                .isInstanceOf(ChatAttachmentService.RejectedException.class)
                .hasFieldOrPropertyWithValue("code", "rejected");
    }

    @Test
    void dailyByteCeilingBlocks() {
        ChatAttachmentService svc = service();
        when(repo.bytesUploadedSince(any(), any()))
                .thenReturn(ChatAttachmentService.ACCOUNT_DAILY_BYTES);
        assertThatThrownBy(() -> svc.upload(tenant, "s1", UUID.randomUUID(), "p.png", PNG))
                .isInstanceOf(ChatAttachmentService.RejectedException.class)
                .hasFieldOrPropertyWithValue("code", "quota_exceeded");
    }

    @Test
    void pdfGetsBoundedExtractedText() {
        byte[] pdf = "%PDF-1.4 fake body".getBytes(StandardCharsets.UTF_8);
        var att = service().upload(tenant, "s1", UUID.randomUUID(), "doc.pdf", pdf);
        assertThat(att.kind()).isEqualTo("DOCUMENT");
        assertThat(att.extractedText()).contains("extracted text");
    }

    @Test
    void resolveForSendEnforcesSessionAndSingleUse() {
        ChatAttachmentService svc = service();
        ChatAttachmentEntity mine = row("a1", "s1", null);
        ChatAttachmentEntity other = row("a2", "OTHER", null);
        ChatAttachmentEntity used = row("a3", "s1", "turn9");
        when(repo.findById("a1")).thenReturn(Optional.of(mine));
        when(repo.findById("a2")).thenReturn(Optional.of(other));
        when(repo.findById("a3")).thenReturn(Optional.of(used));

        assertThat(svc.resolveForSend(tenant, "s1", List.of("a1"))).hasSize(1);
        assertThatThrownBy(() -> svc.resolveForSend(tenant, "s1", List.of("a2")))
                .hasFieldOrPropertyWithValue("code", "not_found");
        assertThatThrownBy(() -> svc.resolveForSend(tenant, "s1", List.of("a3")))
                .hasFieldOrPropertyWithValue("code", "not_found");
        assertThatThrownBy(() -> svc.resolveForSend(tenant, "s1",
                List.of("a1", "a1", "a1", "a1", "a1")))
                .hasFieldOrPropertyWithValue("code", "too_many");
    }

    @Test
    void signedTokenRoundTripsToBytes() {
        ChatAttachmentService svc = service();
        ChatAttachmentEntity e = row("a1", "s1", null);
        when(repo.findById("a1")).thenReturn(Optional.of(e));
        when(blobStore.open(any(), any()))
                .thenReturn(Optional.of(new ByteArrayInputStream(PNG)));

        String url = svc.signedUrl(tenant, "a1");
        String token = url.substring(url.lastIndexOf('/') + 1);
        assertThat(svc.openSigned(token)).hasValueSatisfying(s ->
                assertThat(s.contentType()).isEqualTo("image/png"));
        assertThat(svc.openSigned("garbage")).isEmpty();
    }

    private ChatAttachmentEntity row(String id, String sessionId, String turnId) {
        ChatAttachmentEntity e = new ChatAttachmentEntity(id, tenant.value(), sessionId,
                UUID.randomUUID(), "blob1", "p.png", "image/png", PNG.length, "sha", "IMAGE");
        if (turnId != null) e.reference(turnId);
        return e;
    }
}
