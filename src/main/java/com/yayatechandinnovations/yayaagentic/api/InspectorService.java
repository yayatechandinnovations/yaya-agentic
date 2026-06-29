package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.dto.InspectorDtos;
import com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthTrace;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.persistence.AuditAuthzEntity;
import com.yayatechandinnovations.yayaagentic.persistence.AuditAuthzRepository;
import com.yayatechandinnovations.yayaagentic.persistence.AuthResolverCallEntity;
import com.yayatechandinnovations.yayaagentic.persistence.AuthResolverCallRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the read-only inspector snapshot (intent, working memory, last
 * prompt halves, last denial, last retrieval, tool activity, auth flow) for a
 * session. Shared by the SSE-playground inspector endpoint and the
 * WhatsApp-playground inspector endpoint so both surfaces read identical
 * engine state.
 */
@Service
public class InspectorService {

    private final ConversationEngine engine;
    private final WorkingMemory workingMemory;
    private final AuditAuthzRepository auditAuthz;
    private final AuthResolverCallRepository resolverCalls;
    private final ChannelAuthTrace authTrace;
    private final ObjectMapper json;

    public InspectorService(ConversationEngine engine,
                            WorkingMemory workingMemory,
                            AuditAuthzRepository auditAuthz,
                            AuthResolverCallRepository resolverCalls,
                            ChannelAuthTrace authTrace,
                            ObjectMapper json) {
        this.engine = engine;
        this.workingMemory = workingMemory;
        this.auditAuthz = auditAuthz;
        this.resolverCalls = resolverCalls;
        this.authTrace = authTrace;
        this.json = json;
    }

    public InspectorDtos.InspectorSnapshot snapshot(Ids.SessionId sid) {
        var intent = engine.currentIntent(sid).orElse(null);
        var wm = workingMemory.get(sid);
        var prompt = engine.lastPrompt(sid).orElse(null);
        var denial = latestDenial(sid.value());
        var retrieval = engine.lastRetrieval(sid).orElse(null);
        var toolActivity = engine.lastToolActivity(sid).orElse(null);
        var authFlow = buildAuthFlow(sid, toolActivity);
        return InspectorDtos.InspectorSnapshot.of(
                intent, wm, prompt, denial, retrieval, toolActivity, authFlow, json);
    }

    private AuditAuthzEntity latestDenial(String sessionId) {
        UUID uuid;
        try { uuid = UUID.fromString(sessionId); }
        catch (IllegalArgumentException ignored) { return null; }
        var page = auditAuthz.latestDenialForSession(uuid, PageRequest.of(0, 1));
        return page.isEmpty() ? null : page.getContent().get(0);
    }

    // ---- Auth flow ------------------------------------------------------

    private InspectorDtos.AuthFlowDto buildAuthFlow(
            Ids.SessionId sid,
            List<ConversationEngine.ToolActivity> toolActivity) {

        ChannelAuthTrace.Snapshot trace = authTrace.get(sid).orElse(null);

        List<AuthResolverCallEntity> calls = List.of();
        UUID uuid = tryUuid(sid.value());
        if (uuid != null) {
            calls = resolverCalls.findBySessionIdOrderByStartedAtAsc(uuid);
        }

        // FRESH-session fallback: the INITIAL resolver call is audited with a
        // null session_id (it runs before the engine mints the session id), so
        // the session-keyed query misses it. Recover it by the caller hash —
        // unique per playground session — keeping only rows not tied to a
        // DIFFERENT session (null = this fresh session's pre-id call).
        if (calls.isEmpty() && trace != null
                && trace.tenantId() != null && trace.callerSha256() != null) {
            var recent = resolverCalls.findByTenantIdAndCallerSha256OrderByStartedAtDesc(
                    trace.tenantId(), trace.callerSha256(), PageRequest.of(0, 20)).getContent();
            List<AuthResolverCallEntity> recovered = new ArrayList<>();
            for (AuthResolverCallEntity c : recent) {
                UUID cs = c.getSessionId();
                if (cs == null || sid.value().equals(cs.toString())) recovered.add(c);
            }
            java.util.Collections.reverse(recovered); // back to chronological
            calls = recovered;
        }

        // Section hidden when the session had no channel auth at all (e.g. the
        // standard SSE playground): no binding trace AND no resolver calls.
        if (trace == null && calls.isEmpty()) return null;

        InspectorDtos.BindingStageDto binding = trace == null ? null
                : new InspectorDtos.BindingStageDto(trace.bindingId(), trace.profileId(),
                        trace.resolverId(), trace.onUnresolved());

        List<InspectorDtos.ResolverCallDto> callDtos = new ArrayList<>();
        for (AuthResolverCallEntity c : calls) {
            callDtos.add(new InspectorDtos.ResolverCallDto(
                    c.getResolverName(), c.getReason(), c.getCacheState(), c.getStatus(),
                    c.getHttpStatus(), c.getElapsedMs(), c.getErrorSummary(),
                    c.getStartedAt() == null ? null : c.getStartedAt().toString()));
        }

        InspectorDtos.VerificationDto verification = trace == null ? null
                : new InspectorDtos.VerificationDto(
                        trace.verificationDecision(), trace.verificationDetail());

        String tokenState = trace == null ? null : trace.tokenState();

        List<InspectorDtos.HintDto> hints =
                computeHints(trace, calls, tokenState, toolActivity);

        return new InspectorDtos.AuthFlowDto(binding, callDtos, verification, tokenState, hints);
    }

    /**
     * The "where did it fail?" heuristic (design §7). Collects every applicable
     * hint, most-severe-first. Pure function of the assembled state.
     */
    private List<InspectorDtos.HintDto> computeHints(
            ChannelAuthTrace.Snapshot trace,
            List<AuthResolverCallEntity> calls,
            String tokenState,
            List<ConversationEngine.ToolActivity> toolActivity) {

        List<InspectorDtos.HintDto> hints = new ArrayList<>();

        boolean resolverReturnedToken = false;
        for (AuthResolverCallEntity c : calls) {
            String status = c.getStatus() == null ? "" : c.getStatus();
            switch (status) {
                case "config_error" -> hints.add(fail(
                        "Resolver misconfigured: " + safe(c.getErrorSummary())
                                + ". Check the resolver registration."));
                case "timeout", "transport_error" -> hints.add(fail(
                        "Couldn't reach the resolver webhook (" + status
                                + "). Check the URL, egress allowlist, and network."));
                case "self_auth_failed" -> hints.add(fail(
                        "The resolver rejected yaya's own service credentials (HTTP "
                                + c.getHttpStatus() + ")."));
                case "allowlist_denied" -> hints.add(fail(
                        "Resolver URL is not on the tenant's egress allowlist."));
                case "not_found" -> hints.add(warn(
                        "The caller isn't mapped to an identity by the resolver."));
                case "ok" -> resolverReturnedToken = true;
                default -> { }
            }
        }

        String decision = trace == null ? null : trace.verificationDecision();
        boolean anonymous = "ANONYMOUS".equals(decision);
        boolean rejected = "REJECT".equals(decision);

        // Resolver minted a token but the principal is still anonymous/rejected
        // → the trust chain refused the JWT.
        if (resolverReturnedToken && (anonymous || rejected)) {
            hints.add(fail("The resolver returned a JWT the trust chain rejects. "
                    + "Register a matching tenant_jwt_secrets / tenant_oidc_issuers entry "
                    + "(check the JWT's alg + issuer)."));
        }

        boolean hasPrincipalTool = toolActivity != null && toolActivity.stream()
                .anyMatch(t -> "PRINCIPAL_TOKEN".equals(t.authForwarding()));
        if (anonymous && hasPrincipalTool) {
            hints.add(warn("This turn ran anonymously, but a PRINCIPAL_TOKEN tool was "
                    + "called — it has no identity to forward."));
        }

        // Tool-level 401 analysis — the operator's actual symptom.
        if (toolActivity != null) {
            for (ConversationEngine.ToolActivity t : toolActivity) {
                if (t.httpStatus() == null || t.httpStatus() != 401) continue;
                boolean headerSent = Boolean.TRUE.equals(t.authHeaderPresent());
                if (!headerSent && "PRINCIPAL_TOKEN".equals(t.authForwarding())) {
                    if ("CIPHER_DISABLED".equals(tokenState)) {
                        hints.add(fail("Tool '" + t.toolId() + "' got 401 and no token was "
                                + "forwarded: the session-token cipher key is not configured."));
                    } else {
                        hints.add(fail("Tool '" + t.toolId() + "' got 401 and NO Authorization "
                                + "header was forwarded, even though an upstream token "
                                + "exists. This is the known token-forwarding gap "
                                + "(design §9), not your backend."));
                    }
                } else if (headerSent) {
                    hints.add(warn("Tool '" + t.toolId() + "' got 401 WITH a token attached — "
                            + "the backend rejected it (likely expired or missing scopes). "
                            + "This is downstream of yaya."));
                }
            }
        }

        return hints;
    }

    private static InspectorDtos.HintDto fail(String m) { return new InspectorDtos.HintDto("fail", m); }
    private static InspectorDtos.HintDto warn(String m) { return new InspectorDtos.HintDto("warn", m); }

    private static String safe(String s) { return s == null || s.isBlank() ? "(no detail)" : s; }

    private static UUID tryUuid(String s) {
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }
}
