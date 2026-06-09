package com.yayatechandinnovations.yayaagentic.knowledge.embedding;

import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;

/**
 * Wires Spring AI's {@link EmbeddingModel} (auto-configured by the OpenAI
 * starter when {@code OPENAI_API_KEY} is present) into our internal
 * {@link EmbeddingService} port.
 * <p>
 * Plain class — no stereotype/conditional annotations. {@link EmbeddingConfig}
 * registers it as a {@code @Primary @Bean} gated on the presence of an
 * {@link EmbeddingModel} bean. That keeps the OpenAI-vs-stub fallback purely
 * config-time and avoids the timing fragility of {@code @ConditionalOnBean}
 * on a component-scanned {@code @Service}.
 */
public class SpringAiOpenAiEmbeddingService implements EmbeddingService {

    private static final int DIMENSION = 1536;
    private static final String MODEL_ID = "text-embedding-3-small";

    private final EmbeddingModel model;

    public SpringAiOpenAiEmbeddingService(EmbeddingModel model) {
        this.model = model;
    }

    @Override public int dimension() { return DIMENSION; }
    @Override public String modelId() { return MODEL_ID; }

    @Override
    public float[] embed(String text) {
        return model.embed(text == null ? "" : text);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        // Spring AI's EmbeddingModel exposes a batch overload that returns
        // an EmbeddingResponse; map it through to preserve ordering.
        return model.embed(texts);
    }
}
