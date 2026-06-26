package com.yayatechandinnovations.yayaagentic.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yayatechandinnovations.yayaagentic.channel.ChannelEndpointRef;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingStatus;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBinding;
import com.yayatechandinnovations.yayaagentic.channel.binding.RoutingRule;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;

import java.time.Instant;

/** Wire DTOs for {@code /v1/admin/tenants/{tenantId}/channel-bindings}. */
public final class ChannelBindingDtos {

    private ChannelBindingDtos() {}

    /** POST body. {@code id} fields are inferred from path on PUT. */
    public record BindingRequest(
            EndpointRequest endpoint,
            String          profileId,
            Integer         profileVersion,
            RoutingRule     routing,
            SessionPolicy   sessionPolicy,
            Integer         priority,
            BindingStatus   status
    ) {}

    /** PUT-only update body (endpoint immutable post-create). */
    public record BindingUpdate(
            String          profileId,
            Integer         profileVersion,
            RoutingRule     routing,
            SessionPolicy   sessionPolicy,
            Integer         priority,
            BindingStatus   status
    ) {}

    /** Wire representation of {@link ChannelEndpointRef}. */
    public record EndpointRequest(String kind, String id) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BindingResponse(
            long            id,
            String          tenantId,
            EndpointRequest endpoint,
            String          profileId,
            Integer         profileVersion,
            RoutingRule     routing,
            SessionPolicy   sessionPolicy,
            int             priority,
            BindingStatus   status,
            Instant         createdAt,
            Instant         deactivatedAt
    ) {

        public static BindingResponse of(ChannelBinding b) {
            return new BindingResponse(
                    b.id().value(),
                    b.tenant().value(),
                    new EndpointRequest(b.endpoint().kindName(), b.endpoint().externalId()),
                    b.profile().value(),
                    b.profileVersion().orElse(null),
                    b.routing(),
                    b.sessionPolicy(),
                    b.priority(),
                    b.status(),
                    b.createdAt(),
                    b.deactivatedAt());
        }
    }
}
