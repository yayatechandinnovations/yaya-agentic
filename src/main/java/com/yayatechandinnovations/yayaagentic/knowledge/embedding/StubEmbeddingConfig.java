package com.yayatechandinnovations.yayaagentic.knowledge.embedding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic, offline embedding fallback used by tests and when no real
 * embedding model is configured. We hash the text into a fixed-length
 * float vector — meaningless semantically, but stable enough to round-trip
 * the persistence + retriever path end-to-end.
 * <p>
 * Real RAG quality requires the OpenAI service; this stub exists only so
 * the engine starts without a key and tests don't hit the network.
 */
@Configuration
public class StubEmbeddingConfig {

    @Bean
    @ConditionalOnMissingBean(EmbeddingService.class)
    public EmbeddingService stubEmbeddingService() {
        return new DeterministicHashEmbeddingService();
    }

    static final class DeterministicHashEmbeddingService implements EmbeddingService {

        private static final int DIMENSION = 1536;

        @Override public int dimension() { return DIMENSION; }
        @Override public String modelId() { return "stub-deterministic-hash"; }

        @Override
        public float[] embed(String text) {
            String safe = text == null ? "" : text;
            byte[] seed;
            try {
                seed = MessageDigest.getInstance("SHA-256")
                        .digest(safe.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            float[] v = new float[DIMENSION];
            long state = 0;
            for (byte b : seed) state = state * 31 + (b & 0xff);
            double norm = 0;
            for (int i = 0; i < DIMENSION; i++) {
                state = state * 6364136223846793005L + 1442695040888963407L;
                v[i] = ((state >>> 32) & 0xffff) / 65535f - 0.5f;
                norm += v[i] * v[i];
            }
            float invMag = (float) (1.0 / Math.sqrt(Math.max(norm, 1e-12)));
            for (int i = 0; i < DIMENSION; i++) v[i] *= invMag;
            return v;
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            if (texts == null) return List.of();
            List<float[]> out = new ArrayList<>(texts.size());
            for (String t : texts) out.add(embed(t));
            return out;
        }
    }
}
