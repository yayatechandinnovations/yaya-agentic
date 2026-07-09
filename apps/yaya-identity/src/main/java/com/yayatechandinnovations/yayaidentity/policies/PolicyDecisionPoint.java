package com.yayatechandinnovations.yayaidentity.policies;

import java.util.List;

/**
 * The Policy Decision Point (design §7, SPI §15). {@code decide} evaluates and
 * audits; {@code simulate} is a dry-run (no audit, no persistence) for the policy
 * simulator; {@code decideBatch} answers many checks in one call.
 */
public interface PolicyDecisionPoint {

    Decision decide(DecisionRequest request);

    List<Decision> decideBatch(List<DecisionRequest> requests);

    Decision simulate(DecisionRequest request);
}
