package com.yayatechandinnovations.yayaagentic.knowledge.embedding;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Wires the active {@link EmbeddingService} implementation: the OpenAI-backed
 * one when Spring AI publishes an {@link EmbeddingModel} bean, otherwise the
 * deterministic-hash stub. Both definitions live here as {@code @Bean}
 * methods so the conditional pair evaluates in a single, deterministic phase.
 * <p>
 * Earlier, the OpenAI variant was a component-scanned {@code @Service} with
 * {@code @ConditionalOnBean(EmbeddingModel.class)} and the stub used
 * {@code @ConditionalOnMissingBean(EmbeddingService.class)}. That pair raced
 * at scan time: the OpenAI candidate was visible in the bean-definition map
 * before its own condition had been resolved, so the stub's missing-bean
 * check returned false, and the OpenAI condition then resolved to false too
 * — net effect, zero {@link EmbeddingService} beans and boot failed.
 * <p>
 * Real RAG quality requires the OpenAI service; the stub exists so the
 * engine boots without an OpenAI key and tests don't hit the network.
 */
@Configuration
public class StubEmbeddingConfig {

    @Bean
    @Primary
    @ConditionalOnBean(EmbeddingModel.class)
    public EmbeddingService openAiEmbeddingService(EmbeddingModel model) {
        return new SpringAiOpenAiEmbeddingService(model);
    }

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
