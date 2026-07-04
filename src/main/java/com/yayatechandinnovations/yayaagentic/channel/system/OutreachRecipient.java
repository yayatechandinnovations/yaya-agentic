package com.yayatechandinnovations.yayaagentic.channel.system;

/**
 * WHO an integration message is addressed to — a logical recipient the {@link
 * com.yayatechandinnovations.yayaagentic.outreach.RecipientResolver} resolves to
 * the user's established chat. Sealed so a new addressing kind forces every
 * resolver {@code switch} open. See {@code docs/design/system-messaging-design.md} §6.
 */
public sealed interface OutreachRecipient
        permits OutreachRecipient.ByPrincipal,
                OutreachRecipient.ByRole,
                OutreachRecipient.BySession {

    /** Stable wire name used in the ingest envelope's {@code recipient.kind}. */
    String kindName();

    /** The stable key this recipient hashes to for the durable outreach-session index. */
    String key();

    /** "This operator" — the primary case (SM-0). Delivers to the chat the
     *  verified subject added. */
    record ByPrincipal(String subject) implements OutreachRecipient {
        public ByPrincipal {
            if (subject == null || subject.isBlank())
                throw new IllegalArgumentException("subject required");
            subject = subject.trim();
        }
        @Override public String kindName() { return "PRINCIPAL"; }
        @Override public String key()      { return "principal:" + subject; }
    }

    /** "All on-duty operators of store X" — fans out to each member's chat (SM-3). */
    record ByRole(String role) implements OutreachRecipient {
        public ByRole {
            if (role == null || role.isBlank())
                throw new IllegalArgumentException("role required");
            role = role.trim();
        }
        @Override public String kindName() { return "ROLE"; }
        @Override public String key()      { return "role:" + role; }
    }

    /** The system already holds a conversation id. */
    record BySession(String sessionId) implements OutreachRecipient {
        public BySession {
            if (sessionId == null || sessionId.isBlank())
                throw new IllegalArgumentException("sessionId required");
            sessionId = sessionId.trim();
        }
        @Override public String kindName() { return "SESSION"; }
        @Override public String key()      { return "session:" + sessionId; }
    }
}
