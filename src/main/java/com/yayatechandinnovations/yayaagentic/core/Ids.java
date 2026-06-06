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
        public ProfileId(String value) { this(value, 1); }
    }
    public record CapabilityId(String value) {}
    public record ToolId(String value) {}
    public record KnowledgeSourceId(String value) {}
    public record AuthBindingId(String value) {}
    public record RecorderId(String value) {}
}
