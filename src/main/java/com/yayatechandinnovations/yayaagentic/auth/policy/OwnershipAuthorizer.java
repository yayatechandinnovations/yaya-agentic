package com.yayatechandinnovations.yayaagentic.auth.policy;

import com.yayatechandinnovations.yayaagentic.auth.Authorizer;
import com.yayatechandinnovations.yayaagentic.auth.AuthzContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * Resource-ownership check. Compares a value pulled from the tool args
 * (via {@code resourceArgPath}) against the principal subject (or a
 * specific claim path). Matching value = ALLOW, mismatch = DENY.
 *
 * <p>Path syntax is intentionally simple: dot-segments against the
 * args map (and the principal claims map). Full JSONPath lands in M3 with
 * the OPA authorizer real implementation.</p>
 */
@Component
@Order(20)
public class OwnershipAuthorizer implements Authorizer {

    @Override
    public AuthzDecision authorize(Principal principal,
                                   PermissionRequirement requirement,
                                   Object args,
                                   AuthzContext ctx) {
        if (requirement == null || requirement.ownership() == null) {
            return AuthzDecision.Allow.none();
        }
        PermissionRequirement.ResourceOwnership rule = requirement.ownership();

        Object resourceValue = pluck(args, rule.resourceArgPath());
        Object subjectValue = rule.principalSubjectClaim() == null || rule.principalSubjectClaim().isBlank()
                ? (principal == null ? null : principal.subject())
                : pluck(principal == null ? null : principal.claims(), rule.principalSubjectClaim());

        if (resourceValue == null) {
            return new AuthzDecision.Deny(
                    "I need to know which item you're asking about — could you be more specific?",
                    "ownership check: missing arg at path " + rule.resourceArgPath());
        }
        if (subjectValue == null) {
            return new AuthzDecision.Deny(
                    "your session doesn't appear linked to a specific account",
                    "ownership check: missing principal claim at " + rule.principalSubjectClaim());
        }
        boolean owns = matches(resourceValue, subjectValue);
        if (owns) return AuthzDecision.Allow.none();

        return new AuthzDecision.Deny(
                "that resource isn't linked to your profile",
                "ownership check: principal=" + (principal == null ? null : principal.subject())
                        + " resource=" + resourceValue
                        + " expected=" + subjectValue);
    }

    @SuppressWarnings("unchecked")
    private static Object pluck(Object source, String path) {
        if (source == null || path == null || path.isBlank()) return null;
        String[] segments = path.split("\\.");
        Object cursor = source;
        for (String segment : segments) {
            if (cursor instanceof Map<?, ?> map) {
                cursor = ((Map<String, Object>) map).get(segment);
            } else {
                return null;
            }
            if (cursor == null) return null;
        }
        return cursor;
    }

    private static boolean matches(Object a, Object b) {
        if (a == null || b == null) return false;
        if (a instanceof Iterable<?> it) {
            for (Object e : it) if (matches(e, b)) return true;
            return false;
        }
        if (b instanceof Iterable<?> it) {
            for (Object e : it) if (matches(a, e)) return true;
            return false;
        }
        return a.toString().toLowerCase(Locale.ROOT).equals(b.toString().toLowerCase(Locale.ROOT));
    }
}
