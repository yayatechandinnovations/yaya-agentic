package com.yayatechandinnovations.yayaidentity.policies;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.outbox.OutboxRelay;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Policy CRUD (design §5.7, §9.3). A DENY policy must carry two distinct reasons
 * at creation (#3) — the earliest enforcement point, before the policy can ever
 * produce a leaky denial.
 */
@Service
public class PolicyService {

    private final PolicyRepository policies;
    private final OutboxRelay outbox;

    public PolicyService(PolicyRepository policies, OutboxRelay outbox) {
        this.policies = policies;
        this.outbox = outbox;
    }

    @Transactional
    public Policy create(OrgId org, String name, Policy.Effect effect, String targetJson,
                         String conditionJson, int priority, String userReason, String auditReason) {
        if (effect == Policy.Effect.DENY) {
            if (userReason == null || userReason.isBlank() || auditReason == null || auditReason.isBlank()) {
                throw new IllegalArgumentException("a DENY policy requires both a userReason and an auditReason (#3)");
            }
            if (userReason.equals(auditReason)) {
                throw new IllegalArgumentException("userReason and auditReason must differ (#3)");
            }
        }
        PolicyEntity e = new PolicyEntity(
                "pol_" + UUID.randomUUID().toString().replace("-", ""),
                org.value(), name, effect, targetJson, conditionJson, priority,
                userReason, auditReason, Instant.now());
        policies.save(e);
        outbox.append("policy", e.toDomain().id().value(), "policy.changed",
                "{\"org\":\"" + org.value() + "\",\"name\":\"" + name + "\"}");
        return e.toDomain();
    }

    @Transactional(readOnly = true)
    public List<PolicyEntity> activeFor(OrgId org) {
        return policies.findByOrgIdAndActiveTrue(org.value());
    }

    @Transactional(readOnly = true)
    public List<Policy> listPolicies(OrgId org) {
        return policies.findByOrgId(org.value()).stream().map(PolicyEntity::toDomain).toList();
    }
}
