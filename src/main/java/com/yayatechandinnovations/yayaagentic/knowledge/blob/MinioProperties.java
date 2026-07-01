package com.yayatechandinnovations.yayaagentic.knowledge.blob;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the optional MinIO-backed {@link BlobStore}, bound
 * from {@code yaya.agentic.knowledge.minio}. Only consulted when
 * {@code blob-store=minio}.
 */
@ConfigurationProperties(prefix = "yaya.agentic.knowledge.minio")
public record MinioProperties(String endpoint, String accessKey, String secretKey, String bucket) {
    public MinioProperties {
        if (endpoint == null || endpoint.isBlank()) endpoint = "http://localhost:9000";
        if (bucket == null || bucket.isBlank()) bucket = "yaya-knowledge";
    }
}
