package com.yayatechandinnovations.yayaagentic.tenant.clone;

import java.util.List;
import java.util.Map;

/**
 * Resolved plan that {@link CloneService} produces before any write happens
 * (and emits again as the apply-result so callers can diff against the
 * dry-run). Mirrors the JSON shape in tenant-registry-design §7.2.
 */
public record ClonePlan(
        String sourceTenant,
        String destinationTenant,
        String destinationProfileId,
        ResourceAction profile,
        List<ResourceAction> capabilities,
        List<ResourceAction> tools,
        List<KnowledgeAction> knowledgeSources,
        List<ResourceAction> authBindings,
        List<ResourceAction> recordingStrategies,
        List<PersonalityAction> personality,
        List<String> warnings
) {
    /** Generic resource action — used for profile / capability / tool / auth / recording rows. */
    public record ResourceAction(
            String id,
            String action,                    // CREATE_NEW_VERSION | REUSE_EXISTING | CREATE | SKIP
            Integer fromVersion,
            Integer toVersion,
            List<String> notes
    ) {}

    /** Knowledge source action carries the (possibly rewritten) location. */
    public record KnowledgeAction(
            String id,
            String action,
            Integer fromVersion,
            Integer toVersion,
            String locationKind,
            Map<String, Object> location,
            List<String> notes
    ) {}

    /** Per-locale personality action (§7.8). */
    public record PersonalityAction(
            String locale,
            String action,                    // CREATE | REUSE_EXISTING | SKIP
            Integer fromVersion,
            Integer toVersion,
            List<String> notes
    ) {}
}
