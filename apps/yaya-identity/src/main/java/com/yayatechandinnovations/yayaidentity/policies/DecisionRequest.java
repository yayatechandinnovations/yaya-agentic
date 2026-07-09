package com.yayatechandinnovations.yayaidentity.policies;

import java.util.Map;

/**
 * A PDP query (design §7). {@code resource} carries the resource attributes the
 * PEP knows — including {@code type} + {@code id} for a resource-scoped check
 * (absent ⇒ org-wide) plus attributes a policy may reference (amount, owner,
 * assignedAgent, …). {@code environment} carries request context (time, ip);
 * {@code subjectAttributes} carries subject facts the PEP supplies (e.g. a
 * refund limit) that aren't in identity.
 */
public record DecisionRequest(String org,
                              String subject,
                              String action,
                              Map<String, Object> resource,
                              Map<String, Object> environment,
                              Map<String, Object> subjectAttributes) {

    public DecisionRequest {
        resource = resource == null ? Map.of() : resource;
        environment = environment == null ? Map.of() : environment;
        subjectAttributes = subjectAttributes == null ? Map.of() : subjectAttributes;
    }

    public DecisionRequest(String org, String subject, String action, Map<String, Object> resource) {
        this(org, subject, action, resource, Map.of(), Map.of());
    }

    public String resourceType() {
        Object t = resource.get("type");
        return t == null ? null : t.toString();
    }

    public String resourceId() {
        Object i = resource.get("id");
        return i == null ? null : i.toString();
    }
}
