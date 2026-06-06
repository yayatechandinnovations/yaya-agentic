package com.yayatechandinnovations.yayaagentic.auth.chain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.Authorizer;
import com.yayatechandinnovations.yayaagentic.auth.AuthzContext;
import com.yayatechandinnovations.yayaagentic.auth.AuthzDecision;
import com.yayatechandinnovations.yayaagentic.core.PermissionRequirement;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.AuditAuthzEntity;
import com.yayatechandinnovations.yayaagentic.persistence.AuditAuthzRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Composes ordered {@link Authorizer} candidates. Short-circuits on first
 * DENY. Writes a row to {@code audit_authz} for every final decision —
 * design §5.5 mandates we audit both ALLOW and DENY, with distinct
 * {@code userSafeReason} and {@code auditReason}.
 */
@Component
@Primary
public class AuthorizerChain implements Authorizer {

    private static final Logger log = LoggerFactory.getLogger(AuthorizerChain.class);

    private final List<Authorizer> candidates;
    private final AuditAuthzRepository audit;
    private final ObjectMapper json;

    public AuthorizerChain(List<Authorizer> all,
                           AuditAuthzRepository audit,
                           ObjectMapper json) {
        this.candidates = all.stream()
                .filter(a -> !(a instanceof AuthorizerChain))
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .toList();
        this.audit = audit;
        this.json = json;
    }

    @Override
    @Transactional
    public AuthzDecision authorize(Principal principal,
                                   PermissionRequirement requirement,
                                   Object args,
                                   AuthzContext ctx) {
        List<Map<String, Object>> trace = new ArrayList<>();
        for (Authorizer a : candidates) {
            AuthzDecision decision = a.authorize(principal, requirement, args, ctx);
            trace.add(Map.of(
                    "authorizer", a.getClass().getSimpleName(),
                    "decision", decision instanceof AuthzDecision.Allow ? "ALLOW" : "DENY"));
            if (decision instanceof AuthzDecision.Deny deny) {
                writeAudit(principal, ctx, args, "DENY",
                        deny.userSafeReason(), deny.auditReason(), trace);
                return decision;
            }
        }
        writeAudit(principal, ctx, args, "ALLOW",
                null, "all authorizers passed", trace);
        return AuthzDecision.Allow.none();
    }

    private void writeAudit(Principal principal, AuthzContext ctx, Object args,
                            String decision, String userReason, String auditReason,
                            List<Map<String, Object>> trace) {
        try {
            AuditAuthzEntity row = new AuditAuthzEntity(
                    principal == null || principal.tenant() == null ? "default"
                            : principal.tenant().value(),
                    ctx == null || ctx.sessionId() == null ? null : asUuid(ctx.sessionId().value()),
                    ctx == null || ctx.turnId() == null ? null : asUuid(ctx.turnId().value()),
                    principal == null ? null : principal.subject(),
                    ctx == null ? null : (String) (ctx.attributes() == null ? null : ctx.attributes().get("toolId")),
                    args == null ? null : json.writeValueAsString(args),
                    decision,
                    userReason,
                    auditReason,
                    json.writeValueAsString(Map.of("steps", trace)));
            audit.save(row);
        } catch (JsonProcessingException e) {
            log.warn("authz audit serialization failed", e);
        }
    }

    private static UUID asUuid(String s) {
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }
}
