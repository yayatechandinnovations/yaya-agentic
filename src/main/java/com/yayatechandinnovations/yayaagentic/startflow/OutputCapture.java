package com.yayatechandinnovations.yayaagentic.startflow;

/**
 * Extracts a value from a step's tool result (by RFC-6901 pointer) and writes
 * it to the blackboard. See {@code docs/design/session-start-flows.md} §5.2.
 *
 * @param jsonPointer RFC-6901 into the tool result value ({@code "/account/id"}); {@code ""}/{@code "/"} = whole value
 */
public record OutputCapture(String jsonPointer, CaptureTarget target) {

    public OutputCapture {
        if (jsonPointer == null) jsonPointer = "";
        if (target == null) throw new IllegalArgumentException("OutputCapture.target must be set");
    }
}
