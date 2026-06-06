package com.yayatechandinnovations.yayaagentic.knowledge;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;

public record KnowledgeSource(
        Ids.KnowledgeSourceId id,
        Ids.TenantId tenant,
        String name,
        SourceLocation location,
        IngestionPolicy ingestion,
        RetrievalPolicy retrieval,
        PermissionRequirement access,
        int version
) {}
