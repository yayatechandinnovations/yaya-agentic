package com.yayatechandinnovations.yayaagentic.knowledge;

import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.IntentFrame;

public record RetrievalContext(ExecutionContext execution, IntentFrame intent) {}
