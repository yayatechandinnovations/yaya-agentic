package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.api.dto.InspectorDtos;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Feeds the playground inspector panel: active intent, working memory,
 * last prompt halves, last denial, last retrieval, tool activity. Single
 * read-only endpoint so the UI can refresh the whole panel on every
 * turn-end without coordinating multiple subscriptions.
 */
@RestController
@RequestMapping("/v1/sessions")
public class SessionInspectorController {

    private final InspectorService inspector;

    public SessionInspectorController(InspectorService inspector) {
        this.inspector = inspector;
    }

    @GetMapping("/{id}/inspector")
    public Mono<InspectorDtos.InspectorSnapshot> inspect(@PathVariable("id") String id) {
        return Mono.just(inspector.snapshot(new Ids.SessionId(id)));
    }
}
