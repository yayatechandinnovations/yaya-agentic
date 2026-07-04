package com.yayatechandinnovations.yayaagentic.outreach;

/**
 * A terminal ingest failure the controller maps to an HTTP status. Kept distinct
 * from a silent drop (an eligible-but-nobody-home resolution returns a receipt,
 * not an exception) — this is for misconfiguration / policy violations the
 * trusted caller should learn about.
 */
public class OutreachException extends RuntimeException {

    public enum Kind { SENDER_DISABLED, ADDRESSING_DENIED, UNRESOLVABLE_RECIPIENT, BAD_REQUEST }

    private final Kind kind;

    public OutreachException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() { return kind; }
}
