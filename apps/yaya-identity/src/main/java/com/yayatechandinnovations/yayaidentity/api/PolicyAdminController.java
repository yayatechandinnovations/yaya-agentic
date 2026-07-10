package com.yayatechandinnovations.yayaidentity.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.policies.Policy;
import com.yayatechandinnovations.yayaidentity.policies.PolicyDecisionPoint;
import com.yayatechandinnovations.yayaidentity.policies.PolicyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Policy admin (design §9.3): create a policy, toggle it, and run the simulator
 * (a dry-run decision that persists nothing — US-45/US-61). The list DTO is
 * rich (target, condition, reasons, active) so the console renders policies as
 * readable cards, not opaque names.
 */
@RestController
@RequestMapping("/v1/identity/admin/orgs/{org}/policies")
public class PolicyAdminController {

    private final PolicyService policies;
    private final PolicyDecisionPoint pdp;

    public PolicyAdminController(PolicyService policies, PolicyDecisionPoint pdp) {
        this.policies = policies;
        this.pdp = pdp;
    }

    public record CreatePolicyDto(String name, String effect, JsonNode target, JsonNode condition,
                                  Integer priority, String userReason, String auditReason) {}

    public record ActiveDto(boolean active) {}

    public record PolicyDto(String id, String name, String effect, int priority, boolean active,
                            String targetJson, String conditionJson,
                            String userReason, String auditReason) {
        static PolicyDto of(Policy p) {
            return new PolicyDto(p.id().value(), p.name(), p.effect().name(), p.priority(),
                    p.active(), p.targetJson(), p.conditionJson(), p.userReason(), p.auditReason());
        }
    }

    @GetMapping
    public java.util.List<PolicyDto> list(@PathVariable String org) {
        return policies.listPolicies(new OrgId(org)).stream().map(PolicyDto::of).toList();
    }

    @PostMapping
    public PolicyDto create(@PathVariable String org, @RequestBody CreatePolicyDto body) {
        Policy p = policies.create(
                new OrgId(org), body.name(), Policy.Effect.valueOf(body.effect()),
                body.target().toString(),
                body.condition() == null || body.condition().isNull() ? null : body.condition().toString(),
                body.priority() == null ? 0 : body.priority(),
                body.userReason(), body.auditReason());
        return PolicyDto.of(p);
    }

    @PutMapping("/{policyId}/active")
    public PolicyDto setActive(@PathVariable String org, @PathVariable String policyId,
                               @RequestBody ActiveDto body) {
        return PolicyDto.of(policies.setActive(new OrgId(org), policyId, body.active()));
    }

    @PostMapping("/simulate")
    public DecisionDto simulate(@PathVariable String org, @RequestBody DecideRequestDto request) {
        return DecisionDto.of(pdp.simulate(request.toDomain()));
    }
}
