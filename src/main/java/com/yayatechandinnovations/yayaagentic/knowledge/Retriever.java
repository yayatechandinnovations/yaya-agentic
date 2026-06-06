package com.yayatechandinnovations.yayaagentic.knowledge;

public interface Retriever {
    RetrievalResult retrieve(RetrievalQuery query, RetrievalContext ctx);
}
