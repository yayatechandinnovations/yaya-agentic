package com.yayatechandinnovations.yayaagentic.knowledge.blob;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.knowledge.KnowledgeMetrics;
import com.yayatechandinnovations.yayaagentic.knowledge.extract.ContentTypeSniffer;
import com.yayatechandinnovations.yayaagentic.persistence.KnowledgeBlobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Validates and persists uploaded knowledge files. The gauntlet each file runs
 * before it is stored (design §11): non-empty → per-file size cap → malware scan
 * → content-type sniff (bytes, not the client header) → allow-list → per-tenant
 * corpus quota → {@link BlobStore#store} (which dedups within the tenant).
 *
 * <p>Pure/blocking by design — the controller runs it off the Netty event loop.
 */
@Service
public class KnowledgeUploadService {

    private final BlobStore blobStore;
    private final MalwareScanner scanner;
    private final ContentTypeSniffer sniffer;
    private final KnowledgeBlobRepository blobs;
    private final KnowledgeUploadProperties props;
    private final KnowledgeMetrics metrics;

    public KnowledgeUploadService(BlobStore blobStore, MalwareScanner scanner,
                                  ContentTypeSniffer sniffer, KnowledgeBlobRepository blobs,
                                  KnowledgeUploadProperties props, KnowledgeMetrics metrics) {
        this.blobStore = blobStore;
        this.scanner = scanner;
        this.sniffer = sniffer;
        this.blobs = blobs;
        this.props = props;
        this.metrics = metrics;
    }

    public List<StoredFile> storeAll(TenantId tenant, List<PendingFile> files) {
        return storeAll(tenant, files, defaultPolicy());
    }

    /** The knowledge corpus rules: the configured allow-list, one per-file cap for every type. */
    public UploadPolicy defaultPolicy() {
        return new UploadPolicy(props::isAllowed, ct -> props.maxFileBytes());
    }

    /**
     * Same gauntlet with a caller-owned type policy — what the outreach media
     * library uses so a sender's attachment types and caps are its own, while
     * the scan, the sniff, the quota and the de-dupe stay exactly the knowledge
     * ones. The per-type cap applies to the sniffed type, after the scan.
     */
    public List<StoredFile> storeAll(TenantId tenant, List<PendingFile> files, UploadPolicy policy) {
        if (files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no files in upload");
        }
        long requestBytes = files.stream().mapToLong(f -> f.data().length).sum();
        if (requestBytes > props.maxRequestBytes()) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "upload exceeds per-request limit of " + props.maxRequestBytes() + " bytes");
        }

        long running = blobs.sumSizeBytesByTenantId(tenant.value());
        List<StoredFile> out = new ArrayList<>();
        for (PendingFile file : files) {
            byte[] data = file.data();
            if (data.length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "empty file: " + file.filename());
            }
            if (data.length > props.maxFileBytes()) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        file.filename() + " exceeds per-file limit of " + props.maxFileBytes() + " bytes");
            }
            if (scanner.scan(data, file.filename()) == MalwareScanner.Verdict.INFECTED) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "malware detected in " + file.filename());
            }
            String contentType = sniffer.sniff(data, file.filename());
            if (!policy.allowed().test(contentType)) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "unsupported file type " + contentType + " for " + file.filename());
            }
            long typeCap = policy.maxBytesFor().applyAsLong(contentType);
            if (data.length > typeCap) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        file.filename() + " exceeds the " + contentType + " limit of " + typeCap + " bytes");
            }
            running += data.length;
            if (running > props.maxTenantCorpusBytes()) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "tenant knowledge corpus quota exceeded (" + props.maxTenantCorpusBytes() + " bytes)");
            }
            BlobStore.StoredBlob stored = blobStore.store(
                    tenant, file.filename(), contentType, new ByteArrayInputStream(data), data.length);
            out.add(new StoredFile(stored.blobId(), file.filename(),
                    stored.contentType(), stored.sizeBytes(), stored.sha256()));
        }
        metrics.uploaded(tenant.value(), out.size(), requestBytes);
        return out;
    }

    public record PendingFile(String filename, byte[] data) {}

    /**
     * Which sniffed content types pass and how large each may be. The size
     * check here comes after the sniff, so it is by real type, not by name.
     */
    public record UploadPolicy(Predicate<String> allowed, ToLongFunction<String> maxBytesFor) {
        public UploadPolicy {
            if (allowed == null) throw new IllegalArgumentException("allowed required");
            if (maxBytesFor == null) throw new IllegalArgumentException("maxBytesFor required");
        }
    }

    public record StoredFile(String blobId, String filename, String contentType,
                             long sizeBytes, String sha256) {}
}
