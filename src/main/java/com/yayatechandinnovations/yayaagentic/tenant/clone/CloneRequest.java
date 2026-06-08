package com.yayatechandinnovations.yayaagentic.tenant.clone;

/**
 * Input to {@code CloneService.cloneProfile} per docs/design/tenant-registry-design.md §7.2.
 *
 * @param destinationTenant      target tenant id (must exist and not be archived)
 * @param destinationProfileId   optional override; defaults to the source profile id
 * @param conflictPolicy         what to do when a resource id already exists at the destination
 * @param knowledgeLocationStrategy how to rewrite knowledge source location strings
 * @param personalityPolicy      whether to also clone personality fragments (§7.8)
 * @param dryRun                 true → return the plan, write nothing
 */
public record CloneRequest(
        String destinationTenant,
        String destinationProfileId,
        ConflictPolicy conflictPolicy,
        KnowledgeLocationStrategy knowledgeLocationStrategy,
        PersonalityPolicy personalityPolicy,
        boolean dryRun
) {
    public enum ConflictPolicy { FAIL, SKIP, NEW_VERSION }
    public enum KnowledgeLocationStrategy { RETAIN, TEMPLATE, OMIT }
    public enum PersonalityPolicy { AUTO, ALWAYS, NEVER }

    public CloneRequest withDefaults() {
        return new CloneRequest(
                destinationTenant,
                destinationProfileId,
                conflictPolicy == null ? ConflictPolicy.FAIL : conflictPolicy,
                knowledgeLocationStrategy == null ? KnowledgeLocationStrategy.RETAIN : knowledgeLocationStrategy,
                personalityPolicy == null ? PersonalityPolicy.AUTO : personalityPolicy,
                dryRun);
    }
}
