package com.yayatechandinnovations.yayaagentic.tenant;

/**
 * Raised by {@link BaseUrlResolver} when a path-only {@code urlTemplate}
 * cannot be turned into an absolute URL. The dispatcher surfaces the message
 * as the {@code error} field of a FAILED {@code ToolResult}.
 */
public class BaseUrlResolutionException extends RuntimeException {
    public BaseUrlResolutionException(String message) {
        super(message);
    }
}
