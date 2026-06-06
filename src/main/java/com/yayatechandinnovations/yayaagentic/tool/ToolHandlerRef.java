package com.yayatechandinnovations.yayaagentic.tool;

/**
 * Sealed reference to how a tool runs. Two transports: an in-process Spring
 * bean (resolved by name from the context), or a declarative HTTP endpoint.
 * Both share the same descriptor and authorization pipeline.
 */
public sealed interface ToolHandlerRef
        permits ToolHandlerRef.Bean, ToolHandlerRef.Http {

    record Bean(String beanName) implements ToolHandlerRef {}

    record Http(HttpToolSpec spec) implements ToolHandlerRef {}
}
