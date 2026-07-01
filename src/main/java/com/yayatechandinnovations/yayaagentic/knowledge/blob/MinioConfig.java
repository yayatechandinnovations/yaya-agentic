package com.yayatechandinnovations.yayaagentic.knowledge.blob;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires a {@link MinioClient} only when {@code blob-store=minio}. Creates the
 * bucket on startup if missing so first upload doesn't fail. Self-hosted, no
 * cloud account (design §2, §6).
 */
@Configuration
@ConditionalOnProperty(name = "yaya.agentic.knowledge.blob-store", havingValue = "minio")
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    @Bean
    public MinioClient minioClient(MinioProperties props) {
        MinioClient client = MinioClient.builder()
                .endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey())
                .build();
        try {
            boolean exists = client.bucketExists(
                    BucketExistsArgs.builder().bucket(props.bucket()).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(props.bucket()).build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("MinIO init failed for bucket " + props.bucket(), e);
        }
        return client;
    }
}
