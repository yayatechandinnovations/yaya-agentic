package com.yayatechandinnovations.yayaagentic.outreach.media;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeMetrics;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.BlobStore;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.KnowledgeUploadProperties;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.KnowledgeUploadService;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.MalwareScanner;
import com.yayatechandinnovations.yayaagentic.knowledge.extract.ContentTypeSniffer;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeBlobRepository;
import com.yayatechandinnovations.yayaagentic.persistence.MediaAssetEntity;
import com.yayatechandinnovations.yayaagentic.persistence.MediaAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ticket-share design §8 P1: outreach media has its own allow-list (PDF, PNG,
 * JPEG) and a 5 MiB image cap, independent of the knowledge list, while the
 * scan and the sniff still run.
 */
class OutreachMediaUploadPolicyTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("acme");

    private MalwareScanner scanner;
    private ContentTypeSniffer sniffer;
    private BlobStore blobs;
    private MediaLibraryService library;

    @BeforeEach
    void setUp() {
        scanner = mock(MalwareScanner.class);
        sniffer = mock(ContentTypeSniffer.class);
        blobs = mock(BlobStore.class);
        KnowledgeBlobRepository blobRows = mock(KnowledgeBlobRepository.class);
        when(blobRows.sumSizeBytesByTenantId(anyString())).thenReturn(0L);
        when(scanner.scan(any(), anyString())).thenReturn(MalwareScanner.Verdict.CLEAN);
        when(blobs.store(eq(TENANT), anyString(), anyString(), any(), anyLong())).thenAnswer(inv ->
                new BlobStore.StoredBlob("blob-1", "sha", inv.getArgument(4, Long.class), inv.getArgument(2)));
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        when(assets.save(any())).thenAnswer(inv -> inv.getArgument(0, MediaAssetEntity.class));

        // Knowledge keeps its own (image-less) list: the outreach one must not depend on it.
        KnowledgeUploadService uploads = new KnowledgeUploadService(blobs, scanner, sniffer, blobRows,
                new KnowledgeUploadProperties(null, null, null, null, null, false), mock(KnowledgeMetrics.class));
        library = new MediaLibraryService(uploads, blobs, assets, new OutreachMediaProperties(null, null));
    }

    @Test
    void png_passes_the_outreach_list_even_though_knowledge_rejects_images() {
        when(sniffer.sniff(any(), eq("ticket-1.png"))).thenReturn("image/png");

        var asset = library.upload(TENANT, "ticket-1.png", new byte[]{1, 2, 3}, null);

        assertThat(asset.contentType()).isEqualTo("image/png");
        verify(scanner).scan(any(), eq("ticket-1.png"));
        verify(blobs).store(eq(TENANT), eq("ticket-1.png"), eq("image/png"), any(), eq(3L));
    }

    @Test
    void sniffed_type_wins_over_the_name_and_office_files_are_not_outreach_media() {
        when(sniffer.sniff(any(), eq("ticket.png"))).thenReturn(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        assertThatThrownBy(() -> library.upload(TENANT, "ticket.png", new byte[]{1}, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
        verify(blobs, never()).store(any(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void images_over_five_mib_are_too_large_while_a_pdf_of_that_size_still_passes() {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        when(sniffer.sniff(any(), eq("big.jpg"))).thenReturn("image/jpeg");
        when(sniffer.sniff(any(), eq("big.pdf"))).thenReturn("application/pdf");

        assertThatThrownBy(() -> library.upload(TENANT, "big.jpg", big, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE));
        assertThat(library.upload(TENANT, "big.pdf", big, null).contentType()).isEqualTo("application/pdf");
    }

    @Test
    void malware_is_still_rejected_before_the_type_is_considered() {
        when(scanner.scan(any(), eq("evil.png"))).thenReturn(MalwareScanner.Verdict.INFECTED);

        assertThatThrownBy(() -> library.upload(TENANT, "evil.png", new byte[]{1}, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void properties_default_and_normalise() {
        var props = new OutreachMediaProperties(List.of(" Image/PNG ", "application/pdf"), null);
        assertThat(props.isAllowed("image/png; charset=binary")).isTrue();
        assertThat(props.isAllowed("image/jpeg")).isFalse();
        assertThat(props.maxImageBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(new OutreachMediaProperties(null, null).allowedContentTypes())
                .containsExactly("application/pdf", "image/png", "image/jpeg");
    }
}
