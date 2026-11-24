package com.yayatechandinnovations.yayaagentic.channel.whatsapp.shared;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Picks the workspace an inbound on a platform sender belongs to
 * (whatsapp-shared-sender-design.md §7.2). Routing only names the workspace;
 * whether the engine may run there is the worker's engine gate (§7.5).
 */
public interface SharedInboundRouter {

    RouteDecision route(WhatsAppSender sender, InboundMessageEntity row, Map<String, Object> raw);

    sealed interface RouteDecision {

        /**
         * @param member   the phone is registered staff of {@code tenant} here
         * @param announce say «Estás hablando con …» first: the phone has
         *                 several workspaces and this one is new to it
         * @param replay   a message held while the person was asked; it now
         *                 goes to {@code tenant} (this inbound was the answer)
         */
        record Routed(Ids.TenantId tenant, String reason, boolean member, boolean announce,
                      Optional<String> replay, String label) implements RouteDecision {}

        /** Ask «¿Con cuál negocio?»; {@code holdMessage} keeps this inbound for the answer. */
        record Ask(List<Candidate> candidates, boolean holdMessage) implements RouteDecision {}

        /** No workspace takes it; {@code attributedTo} is the one that wrote to the phone, when only one did. */
        record NotRouted(Optional<Ids.TenantId> attributedTo) implements RouteDecision {}
    }

    /** A workspace the person may pick, as they see it. */
    record Candidate(String assignmentId, Ids.TenantId tenant, String label) {}
}
