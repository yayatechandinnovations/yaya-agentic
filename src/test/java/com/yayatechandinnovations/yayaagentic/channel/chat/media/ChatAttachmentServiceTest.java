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
import com.yayatechandinnovations.yayaagentic.testsupport.TestMedia;
import com.yayatechandinnovations.yayaagentic.usage.UsageMeter;
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
    private MediaTranscriber transcriber;
    private UsageMeter meter;

    @SuppressWarnings("unchecked")
    private ChatAttachmentService service() {
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(repo.bytesUploadedSince(any(), any())).thenReturn(0L);
        when(repo.bytesUploadedBySubjectSince(any(), any(), any())).thenReturn(0L);
        when(blobStore.store(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new BlobStore.StoredBlob("blob1", "sha", PNG.length, "image/png"));
        ObjectProvider<MediaTranscriber> transcribers = mock(ObjectProvider.class);
        when(transcribers.stream()).thenAnswer(i -> transcriber == null ? Stream.empty() : Stream.of(transcriber));
        ObjectProvider<UsageMeter> meters = mock(ObjectProvider.class);
        when(meters.getIfAvailable()).thenAnswer(i -> meter);
        TextExtractor extractor = mock(TextExtractor.class);
        when(extractor.extract(any(InputStream.class), any(), any()))
                .thenReturn(new TextExtractor.Extracted(
                        List.of(new TextExtractor.Page(1, "extracted text")), Map.of()));
        return new ChatAttachmentService(repo, blobStore, (data, name) -> verdict,
                new ContentTypeSniffer(), extractor, new MediaTokenSigner("test-key"),
                transcribers, meters, "http://localhost:8080", 30);
    }

    private static ChatAttachmentService.Uploader subject() {
        return ChatAttachmentService.Uploader.subject("u-1", "panel_owner", "es", null);
    }

    /* ---------------- contract C: subjects, HEIC, codes, voice, retention ---------------- */

    @Test
    void heicIsRefusedWithTheJpgOrPngHint() {
        assertThatThrownBy(() -> service().upload(tenant, "s1", subject(), "IMG_0042.heic", TestMedia.heic()))
                .isInstanceOf(ChatAttachmentService.RejectedException.class)
                .hasFieldOrPropertyWithValue("code", "unsupported_type")
                .hasFieldOrPropertyWithValue("hint", "Mándala como JPG o PNG");
    }

    @Test
    void subjectDailyCeilingBlocksAndIsKeyedOnTheSubject() {
        ChatAttachmentService svc = service();
        when(repo.bytesUploadedBySubjectSince(any(), org.mockito.ArgumentMatchers.eq("u-1"), any()))
                .thenReturn(ChatAttachmentService.ACCOUNT_DAILY_BYTES);
        assertThatThrownBy(() -> svc.upload(tenant, "s1", subject(), "p.png", PNG))
                .hasFieldOrPropertyWithValue("code", "quota_exceeded");
        var other = svc.upload(tenant, "s1",
                ChatAttachmentService.Uploader.subject("u-2", "panel_owner", "es", null), "p.png", PNG);
        assertThat(other.attachment().kind()).isEqualTo("IMAGE");
    }

    @Test
    void imagesAreReencodedAndTheirCodesStored() {
        var up = service().upload(tenant, "s1", subject(), "ticket.png",
                TestMedia.qrPng("4130552218-7781902345", 300));
        assertThat(up.attachment().codes()).extracting(ImageCodes.Code::text).containsExactly("4130552218-7781902345");
        org.mockito.ArgumentCaptor<ChatAttachmentEntity> saved = org.mockito.ArgumentCaptor.forClass(ChatAttachmentEntity.class);
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getCodesJson()).contains("QR_CODE").contains("4130552218-7781902345");
        assertThat(saved.getValue().getPrincipalSubject()).isEqualTo("u-1");

        byte[] withExif = TestMedia.jpegWithExifOrientation(40, 20, 6);
        service().upload(tenant, "s1", subject(), "photo.jpg", withExif);
        org.mockito.ArgumentCaptor<InputStream> stored = org.mockito.ArgumentCaptor.forClass(InputStream.class);
        org.mockito.Mockito.verify(blobStore, org.mockito.Mockito.atLeastOnce())
                .store(any(), any(), any(), stored.capture(), org.mockito.ArgumentMatchers.anyLong());
        try {
            assertThat(ImageHygiene.hasExif(stored.getValue().readAllBytes())).isFalse();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aVoiceNoteOverTwoMinutesIsRefusedAndTheQuotaIsChecked() {
        assertThatThrownBy(() -> service().upload(tenant, "s1", subject(), "note.wav", TestMedia.wav(130)))
                .hasFieldOrPropertyWithValue("code", "too_large")
                .hasFieldOrPropertyWithValue("hint", ChatAttachmentService.AUDIO_TOO_LONG_HINT);

        meter = mock(UsageMeter.class);
        when(meter.hasVoiceAllowance("t1")).thenReturn(false);
        assertThatThrownBy(() -> service().upload(tenant, "s1", subject(), "note.wav", TestMedia.wav(3)))
                .hasFieldOrPropertyWithValue("code", "voice_quota_exceeded");
    }

    @Test
    void transcriptionGetsTheLanguageMetersSecondsAndFailsSoft() {
        meter = mock(UsageMeter.class);
        when(meter.hasVoiceAllowance("t1")).thenReturn(true);
        transcriber = mock(MediaTranscriber.class);
        when(transcriber.supportedContentTypes()).thenReturn(java.util.Set.of("audio/wav"));
        when(transcriber.transcribeBytes(any(), any(), any())).thenReturn(
                new MediaTranscriber.TranscriptionResult("anular el 4411", "es", 1.0, java.time.Duration.ofSeconds(3), "whisper-1"));

        var ok = service().upload(tenant, "s1", subject(), "note.wav", TestMedia.wav(3));
        assertThat(ok.transcriptionFailed()).isFalse();
        assertThat(ok.attachment().transcript()).isEqualTo("anular el 4411");
        assertThat(ok.attachment().durationSeconds()).isEqualTo(3);
        org.mockito.ArgumentCaptor<MediaTranscriber.TranscriptionContext> ctx =
                org.mockito.ArgumentCaptor.forClass(MediaTranscriber.TranscriptionContext.class);
        org.mockito.Mockito.verify(transcriber).transcribeBytes(any(), org.mockito.ArgumentMatchers.eq("audio/wav"), ctx.capture());
        assertThat(ctx.getValue().language()).isEqualTo("es");
        assertThat(ctx.getValue().maxAudioDuration()).isEqualTo(java.time.Duration.ofSeconds(120));
        org.mockito.Mockito.verify(meter).recordVoiceSeconds("t1", "panel_owner", 3);

        when(transcriber.transcribeBytes(any(), any(), any())).thenThrow(new IllegalStateException("down"));
        var failed = service().upload(tenant, "s1", subject(), "note.wav", TestMedia.wav(3));
        assertThat(failed.transcriptionFailed()).isTrue();
        assertThat(failed.attachment().transcript()).isNull();
        assertThat(failed.attachment().id()).startsWith("chatatt_");

        transcriber = null;
        assertThat(service().upload(tenant, "s1", subject(), "note.wav", TestMedia.wav(3)).transcriptionFailed()).isTrue();
    }

    @Test
    void retentionRemovesTheBytesAndKeepsTheRow() {
        ChatAttachmentService svc = service();
        ChatAttachmentEntity old = row("a9", "s1", "turn-1");
        when(repo.findByTurnIdIsNotNullAndBlobIdIsNotNullAndReferencedAtBefore(any())).thenReturn(List.of(old));
        when(repo.countByTenantIdAndBlobId("t1", "blob1")).thenReturn(0L);

        assertThat(svc.sweepExpired()).isEqualTo(1);

        assertThat(old.hasBlob()).isFalse();
        org.mockito.Mockito.verify(blobStore).delete(tenant, "blob1");
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).delete(old);
        // no bytes, no link: history keeps the name only
        when(repo.findBySessionId("s1")).thenReturn(List.of(old));
        assertThat(svc.forSession(tenant, "s1").get("a9").hasBlob()).isFalse();
        assertThat(svc.readBytes(tenant, "a9")).isEmpty();
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
