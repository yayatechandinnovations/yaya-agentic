package com.yayatechandinnovations.yayaagentic.knowledge;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeLoader.IngestionContext;
import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeLoader.RawDocument;
import com.yayatechandinnovations.yayaagentic.knowledge.blob.BlobStore;
import com.yayatechandinnovations.yayaagentic.knowledge.extract.TikaTextExtractor;
import com.yayatechandinnovations.yayaagentic.knowledge.loaders.UploadedFilesLoader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit coverage for the uploaded-files loader — no Spring, no DB. Proves it
 * pulls bytes from the {@link BlobStore}, extracts via Tika, and emits one
 * page-tagged {@link RawDocument} per page so citations can carry a page number.
 */
class UploadedFilesLoaderTest {

    private final TikaTextExtractor extractor = new TikaTextExtractor();

    @Test
    void emits_one_page_tagged_document_for_plain_text() {
        byte[] bytes = "Yaya returns policy: 30 days, no questions asked."
                .getBytes(StandardCharsets.UTF_8);
        var loader = new UploadedFilesLoader(new FakeBlobStore(Map.of("kb_1", bytes)), List.of(extractor));

        var location = new SourceLocation.Uploaded(List.of(
                new SourceLocation.Uploaded.UploadedFile(
                        "kb_1", "returns.txt", "text/plain", bytes.length, "sha")));

        List<RawDocument> docs = loader.load(location, ctx("acme")).toList();

        assertThat(docs).hasSize(1);
        RawDocument doc = docs.get(0);
        assertThat(doc.text()).contains("30 days");
        assertThat(doc.title()).isEqualTo("returns.txt");
        assertThat(doc.metadata()).containsEntry("page", 1)
                .containsEntry("filename", "returns.txt")
                .containsEntry("blobId", "kb_1");
        assertThat(doc.id()).isEqualTo("kb_1#p1");
    }

    @Test
    void requires_tenant_in_ingestion_context() {
        var loader = new UploadedFilesLoader(new FakeBlobStore(Map.of()), List.of(extractor));
        var location = new SourceLocation.Uploaded(List.of(
                new SourceLocation.Uploaded.UploadedFile("kb_x", "f.txt", "text/plain", 1, "s")));

        assertThatThrownBy(() -> loader.load(location, new IngestionContext("c", Map.of())).toList())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void refuses_infected_blob() {
        byte[] bytes = "malicious".getBytes(StandardCharsets.UTF_8);
        var store = new FakeBlobStore(Map.of("kb_bad", bytes));
        store.scanStatus.put("kb_bad", "INFECTED");
        var loader = new UploadedFilesLoader(store, List.of(extractor));
        var location = new SourceLocation.Uploaded(List.of(
                new SourceLocation.Uploaded.UploadedFile("kb_bad", "x.txt", "text/plain", bytes.length, "s")));

        assertThatThrownBy(() -> loader.load(location, ctx("acme")).toList())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("infected");
    }

    private static IngestionContext ctx(String tenant) {
        return new IngestionContext("corr", Map.of("tenantId", tenant));
    }

    /** In-memory BlobStore — enough to exercise open()/stat() without a DB. */
    private static final class FakeBlobStore implements BlobStore {
        private final Map<String, byte[]> data;
        private final Map<String, String> scanStatus = new HashMap<>();

        FakeBlobStore(Map<String, byte[]> data) { this.data = data; }

        @Override public StoredBlob store(TenantId t, String f, String ct, InputStream in, long n) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<InputStream> open(TenantId tenant, String blobId) {
            return Optional.ofNullable(data.get(blobId)).map(ByteArrayInputStream::new);
        }
        @Override public Optional<BlobMetadata> stat(TenantId tenant, String blobId) {
            if (!data.containsKey(blobId)) return Optional.empty();
            return Optional.of(new BlobMetadata("f", "text/plain", data.get(blobId).length,
                    "sha", scanStatus.getOrDefault(blobId, "CLEAN")));
        }
        @Override public void delete(TenantId tenant, String blobId) { data.remove(blobId); }
    }
}
