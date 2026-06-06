package com.yayatechandinnovations.yayaagentic.tool;

import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.core.Turn;

import java.util.Map;

/**
 * Single dispatch point for the engine. Validates args against schema,
 * delegates to the bound {@link Authorizer} chain via the engine, then
 * dispatches through the right transport (Bean or Http) based on the
 * descriptor's {@link ToolHandlerRef}.
 */
public interface ToolExecutor {
    Turn.ToolResult execute(ToolDescriptor descriptor,
                            Map<String, Object> args,
                            ExecutionContext ctx);
}
