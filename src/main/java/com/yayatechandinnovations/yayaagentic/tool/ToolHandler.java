package com.yayatechandinnovations.yayaagentic.tool;

import com.yayatechandinnovations.yayaagentic.core.ExecutionContext;

/**
 * Contract for in-process (Bean) tool implementations. HTTP tools get an
 * adapter generated from {@link HttpToolSpec} at dispatch time.
 */
public interface ToolHandler<I, O> {
    O execute(I input, ExecutionContext ctx) throws ToolException;
}
