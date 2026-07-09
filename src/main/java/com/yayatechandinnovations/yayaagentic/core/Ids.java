package com.yayatechandinnovations.yayaagentic.core;

/**
 * Typed identifier records. Keeping these as records (not String) prevents
 * cross-wiring between e.g. ProfileId and CapabilityId at compile time.
 */
public final class Ids {

    private Ids() {}

    public record TenantId(String value) {}
    public record SessionId(String value) {}
    public record TurnId(String value) {}
    public record ProfileId(String value, int version) {
        /**
         * Sentinel version meaning "unpinned — resolve to the latest version
         * at session start" (M5 latest-active resolution). A binding/mapping
         * with no explicit version pin carries this; the engine swaps it for a
         * concrete {@code profile.version()} once, at {@code start()}, and pins
         * the session there for its lifetime. Never persisted on a Session.
         */
        public static final int LATEST_VERSION = 0;

        public ProfileId(String value) { this(value, 1); }

        /** An unpinned id that resolves to the latest version at session start. */
        public static ProfileId latest(String value) { return new ProfileId(value, LATEST_VERSION); }

        public boolean isLatest() { return version == LATEST_VERSION; }
    }
    public record CapabilityId(String value) {}
    public record ToolId(String value) {}
    public record KnowledgeSourceId(String value) {}
    public record AuthBindingId(String value) {}
    public record RecorderId(String value) {}

    /** A registered integration/system sender that may inject messages into a
     *  conversation. Tenant-scoped config, not a session row. See
     *  {@code docs/design/system-messaging-design.md} §3. */
    public record SystemSenderId(String value) {}

    /** One ingested system→user message (idempotent on the sender's dedup key).
     *  See {@code docs/design/system-messaging-design.md} §5. */
    public record OutreachId(String value) {}

    /**
     * Versioned like a {@link ProfileId} — a Session Start Flow is tenant-scoped
     * config pinned at {@code start()}, not a session row. See
     * {@code docs/design/session-start-flows.md} §6.
     */
    public record StartFlowId(String value, int version) {
        /** Sentinel meaning "resolve to the latest version at session start". */
        public static final int LATEST_VERSION = 0;

        public StartFlowId(String value) { this(value, 1); }

        public static StartFlowId latest(String value) { return new StartFlowId(value, LATEST_VERSION); }

        public boolean isLatest() { return version == LATEST_VERSION; }
    }
}
