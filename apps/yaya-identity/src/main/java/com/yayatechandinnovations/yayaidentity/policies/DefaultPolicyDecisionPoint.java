package com.yayatechandinnovations.yayaidentity.policies;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.PermissionKey;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import com.yayatechandinnovations.yayaidentity.platform.audit.AuditEntry;
import com.yayatechandinnovations.yayaidentity.platform.audit.AuditLog;
import com.yayatechandinnovations.yayaidentity.roles.RoleAssignmentService;
import com.yayatechandinnovations.yayaidentity.roles.Scope;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Policy Decision Point (design §7). Evaluation order:
 * <ol>
 *   <li>Resolve the subject's effective permissions for the target from scoped
 *       role assignments (RBAC, I2) — absence is a base deny.</li>
 *   <li>Evaluate applicable policies. Any matching <b>DENY</b> wins
 *       (deny-overrides); a matching <b>PERMIT</b> can grant what RBAC didn't.</li>
 *   <li>No explicit permit ⇒ <b>default deny</b>.</li>
 *   <li>Emit the decision to the append-only audit log.</li>
 * </ol>
 * Apps are PEPs; this is the only place policy is evaluated (agent §12).
 */
@Service
public class DefaultPolicyDecisionPoint implements PolicyDecisionPoint {

    private static final String DENIED_USER_REASON = "You don't have permission to perform this action.";

    private final RoleAssignmentService assignments;
    private final PolicyService policies;
    private final ConditionEvaluator conditions;
    private final AuditLog audit;
    private final ObjectMapper mapper;

    public DefaultPolicyDecisionPoint(RoleAssignmentService assignments, PolicyService policies,
                                      ConditionEvaluator conditions, AuditLog audit, ObjectMapper mapper) {
        this.assignments = assignments;
        this.policies = policies;
        this.conditions = conditions;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Override
    public Decision decide(DecisionRequest request) {
        Decision decision = evaluate(request);
        record(request, decision);
        return decision;
    }

    @Override
    public List<Decision> decideBatch(List<DecisionRequest> requests) {
        return requests.stream().map(this::decide).toList();
    }

    @Override
    public Decision simulate(DecisionRequest request) {
        return evaluate(request);   // dry-run: no audit, no side effects
    }

    private Decision evaluate(DecisionRequest req) {
        OrgId org = new OrgId(req.org());
        UserId subject = new UserId(req.subject());
        Scope target = req.resourceType() != null && req.resourceId() != null
                ? new Scope.Resource(req.resourceType(), req.resourceId())
                : new Scope.Org(org);

        // 1. RBAC base.
        Set<PermissionKey> effective = assignments.effectivePermissions(subject, org, target);
        boolean rbacPermits = effective.stream().anyMatch(k -> k.value().equals(req.action()));

        // 2. Policies. Any matching DENY overrides; a matching PERMIT can grant.
        Map<String, Object> data = attributeBags(req, org, subject);
        Decision.Deny denyOverride = null;
        int denyPriority = Integer.MIN_VALUE;
        boolean policyPermits = false;

        for (PolicyEntity p : policies.activeFor(org)) {
            if (!appliesTo(p, req)) continue;
            if (!conditions.matches(p.getConditionJson(), data)) continue;
            if (p.effect() == Policy.Effect.DENY) {
                if (p.getPriority() > denyPriority) {
                    denyOverride = new Decision.Deny(p.getUserReason(), p.getAuditReason());
                    denyPriority = p.getPriority();
                }
            } else {
                policyPermits = true;
            }
        }

        if (denyOverride != null) {
            return denyOverride;                                    // deny-overrides
        }
        if (rbacPermits || policyPermits) {
            return new Decision.Permit(null);
        }
        // 3. Default deny — generic user reason, specific audit reason (#3).
        return new Decision.Deny(DENIED_USER_REASON,
                "default-deny: no RBAC permission '" + req.action() + "' and no PERMIT policy for subject "
                        + req.subject() + " on " + targetDesc(req));
    }

    private Map<String, Object> attributeBags(DecisionRequest req, OrgId org, UserId subject) {
        Map<String, Object> subjectBag = new HashMap<>();
        subjectBag.put("id", req.subject());
        subjectBag.put("org", req.org());
        subjectBag.put("roles", assignments.tokenRoles(subject, org).stream()
                .map(m -> m.get("role")).distinct().toList());
        subjectBag.putAll(req.subjectAttributes());   // PEP-supplied subject facts (e.g. refundLimit)

        Map<String, Object> data = new HashMap<>();
        data.put("subject", subjectBag);
        data.put("resource", req.resource());
        data.put("env", req.environment());
        return data;
    }

    @SuppressWarnings("unchecked")
    private boolean appliesTo(PolicyEntity policy, DecisionRequest req) {
        try {
            Map<String, Object> target = mapper.readValue(policy.getTargetJson(), Map.class);
            List<String> actions = (List<String>) target.getOrDefault("actions", List.of());
            if (!actions.contains("*") && !actions.contains(req.action())) {
                return false;
            }
            List<String> resourceTypes = (List<String>) target.getOrDefault("resourceTypes", List.of());
            return resourceTypes.isEmpty() || resourceTypes.contains(req.resourceType());
        } catch (Exception e) {
            throw new IllegalStateException("invalid policy target for policy: " + e.getMessage(), e);
        }
    }

    private void record(DecisionRequest req, Decision decision) {
        AuditEntry entry = switch (decision) {
            case Decision.Permit ignored -> AuditEntry.permit(req.org(), req.subject(), req.action(), targetDesc(req));
            case Decision.Deny d -> AuditEntry.deny(req.org(), req.subject(), req.action(), targetDesc(req),
                    d.userSafeReason(), d.auditReason());
        };
        audit.record(entry);
    }

    private static String targetDesc(DecisionRequest req) {
        return req.resourceType() != null ? req.resourceType() + ":" + req.resourceId() : "org";
    }
}
