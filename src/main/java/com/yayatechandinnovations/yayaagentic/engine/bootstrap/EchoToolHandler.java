package com.yayatechandinnovations.yayaagentic.engine.bootstrap;

import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;
import com.yayatechandinnovations.yayaagentic.tool.ToolException;
import com.yayatechandinnovations.yayaagentic.tool.ToolHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The M0 demo tool. Input: {"text": "..."}. Output: {"echo": "..."}.
 * Referenced by hello-world@1 via {@code ToolHandlerRef.Bean("echoTool")}.
 */
@Component("echoTool")
public class EchoToolHandler implements ToolHandler<Map<String, Object>, Map<String, Object>> {

    @Override
    public Map<String, Object> execute(Map<String, Object> input, ExecutionContext ctx) {
        Object text = input == null ? null : input.get("text");
        if (text == null) {
            throw new ToolException("echo: required arg 'text' is missing");
        }
        return Map.of("echo", text.toString());
    }
}
