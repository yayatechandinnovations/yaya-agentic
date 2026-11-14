package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.core.TurnChannel;
import com.yayatechandinnovations.yayaagentic.api.dto.PublicChatDtos;
import com.yayatechandinnovations.yayaagentic.auth.AuthContext;
import com.yayatechandinnovations.yayaagentic.auth.Authenticator;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatChannelAdapter;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointService;
import com.yayatechandinnovations.yayaagentic.channel.chat.EntryPointStatus;
import com.yayatechandinnovations.yayaagentic.channel.system.OutreachRecipient;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.engine.ActionNotPermittedException;
import com.yayatechandinnovations.yayaagentic.engine.ConversationEngine;
import com.yayatechandinnovations.yayaagentic.engine.UserMessage;
import com.yayatechandinnovations.yayaagentic.outreach.MediationService;
import com.yayatechandinnovations.yayaagentic.outreach.SystemReplyToolBootstrap;
import com.yayatechandinnovations.yayaagentic.persistence.OutreachSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Optional;

/**
 * The operator's reply into a system-participant thread (§8, §12). Distinct from
 * the ordinary chat send: the target is a durable outreach session (minted at
 * ingest, possibly long-evicted), so we {@link ConversationEngine#resume rehydrate}
 * it under the operator's verified identity, {@link MediationService#primeReply
 * arm} the correlation + capability mask, then run the turn. The response tool —
 * if the agent calls it — routes back to the pinned originator; the LLM never
 * chooses the destination.
 */
@RestController
@RequestMapping("/v1/chat/e/{handle}/outreach/{sessionId}/messages")
public class OutreachReplyController {

    private final ChatEntryPointService entryPoints;
    private final Authenticator authenticator;
    private final ConversationEngine engine;
    private final MediationService mediation;
    private final OutreachSessionRepository outreachSessions;
    private final ChatChannelAdapter chatAdapter;

    public OutreachReplyController(ChatEntryPointService entryPoints,
                                   Authenticator authenticator,
                                   ConversationEngine engine,
                                   MediationService mediation,
                                   OutreachSessionRepository outreachSessions,
                                   ChatChannelAdapter chatAdapter) {
        this.entryPoints = entryPoints;
        this.authenticator = authenticator;
        this.engine = engine;
        this.mediation = mediation;
        this.outreachSessions = outreachSessions;
        this.chatAdapter = chatAdapter;
    }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> reply(@PathVariable("handle") String handle,
                                               @PathVariable("sessionId") String sessionId,
                                               @RequestBody PublicChatDtos.MessageRequest body,
                                               ServerWebExchange exchange) {
        Ids.SessionId sid = new Ids.SessionId(sessionId);
        return Flux.defer(() -> {
            ChatEntryPoint ep = requirePublic(handle);
            Principal operator = requireVerified(ep, exchange);

            // Ownership at the outreach layer: this outreach session must be the
            // one indexed to THIS verified recipient on THIS entry point.
            String recipientKey = new OutreachRecipient.ByPrincipal(operator.subject()).key();
            outreachSessions.findByTenantIdAndEntryPointIdAndRecipientKey(
                            ep.tenant().value(), ep.id().value(), recipientKey)
                    .filter(o -> o.getSessionId().equals(sessionId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such thread"));

            AuthContext auth = new AuthContext(ep.tenant(),
                    exchange.getRequest().getHeaders().toSingleValueMap(), Optional.empty());
            // Rehydrate under the operator (also re-verifies ownership) then arm
            // the reply: correlation pinned + capability mask (§9.1).
            engine.resume(sid, auth);
            mediation.primeReply(ep.tenant(), sid);

            return com.yayatechandinnovations.yayaagentic.usage.QuotaRefusals.spoken(engine.send(sid, new UserMessage(body.text(), Map.of()), auth.onChannel(TurnChannel.CHAT)))
                    .map(chatAdapter::toSse)
                    .doFinally(sig -> resolveIfReplied(sid));
        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorMap(ActionNotPermittedException.class,
                  e -> new ResponseStatusException(HttpStatus.FORBIDDEN, "not permitted"));
    }

    /** Tear down the mask + mark resolved only once the reply tool actually fired —
     *  an off-topic turn leaves the thread pending so the user can still answer. */
    private void resolveIfReplied(Ids.SessionId sid) {
        boolean replied = engine.lastToolActivity(sid).map(list -> list.stream().anyMatch(a ->
                SystemReplyToolBootstrap.RESPONSE_TOOL.value().equals(a.toolId())
                        && "OK".equals(a.status()))).orElse(false);
        if (replied) mediation.resolve(sid);
    }

    private ChatEntryPoint requirePublic(String handle) {
        ChatEntryPoint ep = entryPoints.findByHandle(handle)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown handle"));
        if (ep.status() != EntryPointStatus.ACTIVE && ep.status() != EntryPointStatus.PAUSED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown handle");
        }
        return ep;
    }

    private Principal requireVerified(ChatEntryPoint ep, ServerWebExchange exchange) {
        AuthContext ctx = new AuthContext(ep.tenant(),
                exchange.getRequest().getHeaders().toSingleValueMap(), Optional.empty());
        return authenticator.tryAuthenticate(ctx)
                .filter(p -> !isAnonymous(p))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "sign-in required"));
    }

    private static boolean isAnonymous(Principal p) {
        String s = p.subject();
        return s == null || s.startsWith("anonymous-") || s.contains(":anon:");
    }
}
