package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.dto.InspectorDtos;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.persistence.AuditAuthzEntity;
import com.yayatechandinnovations.yayaagentic.persistence.AuditAuthzRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Builds the read-only inspector snapshot (intent, working memory, last
 * prompt halves, last denial, last retrieval, tool activity) for a session.
 * Shared by the SSE-playground inspector endpoint and the WhatsApp-playground
 * inspector endpoint so both surfaces read identical engine state.
 */
@Service
public class InspectorService {

    private final ConversationEngine engine;
    private final WorkingMemory workingMemory;
    private final AuditAuthzRepository auditAuthz;
    private final ObjectMapper json;

    public InspectorService(ConversationEngine engine,
                            WorkingMemory workingMemory,
                            AuditAuthzRepository auditAuthz,
                            ObjectMapper json) {
        this.engine = engine;
        this.workingMemory = workingMemory;
        this.auditAuthz = auditAuthz;
        this.json = json;
    }

    public InspectorDtos.InspectorSnapshot snapshot(Ids.SessionId sid) {
        var intent = engine.currentIntent(sid).orElse(null);
        var wm = workingMemory.get(sid);
        var prompt = engine.lastPrompt(sid).orElse(null);
        var denial = latestDenial(sid.value());
        var retrieval = engine.lastRetrieval(sid).orElse(null);
        var toolActivity = engine.lastToolActivity(sid).orElse(null);
        return InspectorDtos.InspectorSnapshot.of(intent, wm, prompt, denial, retrieval, toolActivity, json);
    }

    private AuditAuthzEntity latestDenial(String sessionId) {
        UUID uuid;
        try { uuid = UUID.fromString(sessionId); }
        catch (IllegalArgumentException ignored) { return null; }
        var page = auditAuthz.latestDenialForSession(uuid, PageRequest.of(0, 1));
        return page.isEmpty() ? null : page.getContent().get(0);
    }
}
