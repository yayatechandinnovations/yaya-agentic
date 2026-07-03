package com.yayatechandinnovations.yayaagentic.engine.bootstrap;

import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Demo account-lookup for the seeded "welcome-back" Session Start Flow — stands
 * in for a real returning-customer lookup so operators can try a flow end-to-end
 * without wiring a backend. Returns a recognized customer. Registered by
 * {@link StartFlowDemoBootstrap}.
 */
@Component("startflowDemoLookupTool")
public class StartFlowDemoLookupTool implements ToolHandler<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        return Map.of("found", true, "name", "Ana", "tier", "gold");
    }
}
