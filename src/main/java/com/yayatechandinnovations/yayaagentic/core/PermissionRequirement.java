package com.yayatechandinnovations.yayaagentic.core;

import java.util.List;
import java.util.Set;

/**
 * Declarative permission requirement attached to tools and knowledge sources.
 * Enforced by the {@code Authorizer} chain, NOT by the LLM. See design §5.5.
 */
public record PermissionRequirement(
        Set<String> requiredScopes,
        List<AttributeMatch> requiredAttributes,
        ResourceOwnership ownership
) {
    public record AttributeMatch(String claimPath, String op, Object expected) {}

    public record ResourceOwnership(String resourceArgPath, String principalSubjectClaim) {}

    public static PermissionRequirement none() {
        return new PermissionRequirement(Set.of(), List.of(), null);
    }
}
