package com.yayatechandinnovations.yayaagentic.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.dto.InspectorDtos;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.memory.WorkingMemory;
import com.yayatechandinnovations.yayaagentic.persistence.AuditAuthzEntity;
import com.yayatechandinnovations.yayaagentic.persistence.AuditAuthzRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Feeds the playground inspector panel: active intent, working memory,
 * last prompt halves, last denial. Single read-only endpoint so the UI
 * can refresh the whole panel on every turn-end without coordinating
 * multiple subscriptions.
 */
@RestController
@RequestMapping("/v1/sessions")
public class SessionInspectorController {

    private final ConversationEngine engine;
    private final WorkingMemory workingMemory;
    private final AuditAuthzRepository auditAuthz;
    private final ObjectMapper json;

    public SessionInspectorController(ConversationEngine engine,
                                      WorkingMemory workingMemory,
                                      AuditAuthzRepository auditAuthz,
                                      ObjectMapper json) {
        this.engine = engine;
        this.workingMemory = workingMemory;
        this.auditAuthz = auditAuthz;
        this.json = json;
    }

    @GetMapping("/{id}/inspector")
    public Mono<InspectorDtos.InspectorSnapshot> inspect(@PathVariable("id") String id) {
        Ids.SessionId sid = new Ids.SessionId(id);
        var intent = engine.currentIntent(sid).orElse(null);
        var wm = workingMemory.get(sid);
        var prompt = engine.lastPrompt(sid).orElse(null);
        var denial = latestDenial(id);
        var retrieval = engine.lastRetrieval(sid).orElse(null);
        return Mono.just(InspectorDtos.InspectorSnapshot.of(intent, wm, prompt, denial, retrieval, json));
    }

    private AuditAuthzEntity latestDenial(String sessionId) {
        UUID uuid;
        try { uuid = UUID.fromString(sessionId); }
        catch (IllegalArgumentException ignored) { return null; }
        var page = auditAuthz.latestDenialForSession(uuid, PageRequest.of(0, 1));
        return page.isEmpty() ? null : page.getContent().get(0);
    }
}
