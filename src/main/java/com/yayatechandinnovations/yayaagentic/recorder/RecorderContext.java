package com.yayatechandinnovations.yayaagentic.recorder;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;

import java.util.Map;

public record RecorderContext(
        Ids.TenantId tenant,
        Principal principal,
        String traceId,
        Map<String, Object> attributes
) {}
