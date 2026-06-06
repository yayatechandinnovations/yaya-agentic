package com.yayatechandinnovations.yayaagentic.knowledge;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.util.List;
import java.util.Map;

public record RetrievalQuery(
        String text,
        List<Ids.KnowledgeSourceId> sources,
        Map<String, Object> metadataFilter
) {}
