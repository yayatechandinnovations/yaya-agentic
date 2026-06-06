package com.yayatechandinnovations.yayaagentic.knowledge;

public record RetrievalPolicy(
        int topK,
        double minScore,
        double vectorWeight,
        double keywordWeight,
        boolean rerank
) {
    public static RetrievalPolicy defaults() {
        return new RetrievalPolicy(6, 0.0, 0.7, 0.3, false);
    }
}
