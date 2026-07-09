package com.yayatechandinnovations.yayaidentity.policies;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.PolicyId;

/**
 * A conditional authorization rule (design §5.7). {@code target} selects the
 * actions/resource-types it applies to; {@code condition} (JSON-logic) further
 * narrows it. A DENY carries the two reasons (#3).
 */
public record Policy(PolicyId id,
                     OrgId org,
                     String name,
                     Effect effect,
                     String targetJson,
                     String conditionJson,
                     int priority,
                     String userReason,
                     String auditReason,
                     boolean active) {

    public enum Effect { PERMIT, DENY }
}
