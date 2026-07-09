package com.yayatechandinnovations.yayaidentity.api;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.PermissionKey;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import com.yayatechandinnovations.yayaidentity.policies.PolicyDecisionPoint;
import com.yayatechandinnovations.yayaidentity.roles.RoleAssignmentService;
import com.yayatechandinnovations.yayaidentity.roles.Scope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The fine-grained AuthZ seam (design §9.2): {@code decide}, {@code decide-batch},
 * and effective {@code permissions}. Token-protected (service token) by the
 * resource-server chain.
 */
@RestController
@RequestMapping("/pdp")
public class PdpController {

    private final PolicyDecisionPoint pdp;
    private final RoleAssignmentService assignments;

    public PdpController(PolicyDecisionPoint pdp, RoleAssignmentService assignments) {
        this.pdp = pdp;
        this.assignments = assignments;
    }

    @PostMapping("/decide")
    public DecisionDto decide(@RequestBody DecideRequestDto request) {
        return DecisionDto.of(pdp.decide(request.toDomain()));
    }

    @PostMapping("/decide-batch")
    public List<DecisionDto> decideBatch(@RequestBody List<DecideRequestDto> requests) {
        return pdp.decideBatch(requests.stream().map(DecideRequestDto::toDomain).toList())
                .stream().map(DecisionDto::of).toList();
    }

    @GetMapping("/permissions")
    public List<String> permissions(@RequestParam String org,
                                    @RequestParam String subject,
                                    @RequestParam(required = false) String resourceType,
                                    @RequestParam(required = false) String resourceId) {
        Scope target = resourceType != null && resourceId != null
                ? new Scope.Resource(resourceType, resourceId)
                : new Scope.Org(new OrgId(org));
        return assignments.effectivePermissions(new UserId(subject), new OrgId(org), target)
                .stream().map(PermissionKey::value).sorted().toList();
    }
}
