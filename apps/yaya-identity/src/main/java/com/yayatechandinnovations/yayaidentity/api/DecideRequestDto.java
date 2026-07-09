package com.yayatechandinnovations.yayaidentity.api;

import com.yayatechandinnovations.yayaidentity.policies.DecisionRequest;

import java.util.Map;

/** Wire form of a PDP query (design §9.2). */
public record DecideRequestDto(String org,
                               String subject,
                               String action,
                               Map<String, Object> resource,
                               Map<String, Object> environment,
                               Map<String, Object> subjectAttributes) {

    public DecisionRequest toDomain() {
        return new DecisionRequest(org, subject, action, resource, environment, subjectAttributes);
    }
}
