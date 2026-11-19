package com.yayatechandinnovations.yayaagentic.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yayatechandinnovations.yayaagentic.channel.ChannelEndpointRef;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingStatus;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBinding;
import com.yayatechandinnovations.yayaagentic.channel.binding.OnOverrideDenied;
import com.yayatechandinnovations.yayaagentic.channel.binding.OnUnresolvedPolicy;
import com.yayatechandinnovations.yayaagentic.channel.binding.RoutingRule;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;

import java.time.Instant;
import java.util.List;

/** Wire DTOs for {@code /v1/admin/tenants/{tenantId}/channel-bindings}. */
public final class ChannelBindingDtos {

    private ChannelBindingDtos() {}

    /**
     * POST body. {@code id} fields are inferred from path on PUT.
     *
     * @param authResolverId  optional FK to {@code channel_auth_resolvers.id}.
     *                        Null = FAQ-mode binding (no resolver). The
     *                        admin form looks the row up by name and sends
     *                        its id here.
     * @param onUnresolved    'REJECT' | 'ANONYMOUS' | 'AUTO_REPLY'. Defaults to
     *                        REJECT server-side when null.
     * @param unresolvedReplyText  the fixed line AUTO_REPLY answers an unknown
     *                        caller with (required for AUTO_REPLY, ≤ 1000 chars).
     */
    public record BindingRequest(
            EndpointRequest endpoint,
            String          profileId,
            Integer         profileVersion,
            RoutingRule     routing,
            SessionPolicy   sessionPolicy,
            Integer         priority,
            BindingStatus   status,
            Long            authResolverId,
            OnUnresolvedPolicy onUnresolved,
            List<String>    allowedOverrideProfiles,
            OnOverrideDenied onOverrideDenied,
            String          unresolvedReplyText
    ) {
        public BindingRequest(EndpointRequest endpoint, String profileId, Integer profileVersion,
                              RoutingRule routing, SessionPolicy sessionPolicy, Integer priority,
                              BindingStatus status, Long authResolverId, OnUnresolvedPolicy onUnresolved,
                              List<String> allowedOverrideProfiles, OnOverrideDenied onOverrideDenied) {
            this(endpoint, profileId, profileVersion, routing, sessionPolicy, priority, status, authResolverId,
                    onUnresolved, allowedOverrideProfiles, onOverrideDenied, null);
        }
    }

    /**
     * PUT-only update body (endpoint immutable post-create). A null
     * {@code unresolvedReplyText} keeps the stored text; "" clears it.
     */
    public record BindingUpdate(
            String          profileId,
            Integer         profileVersion,
            RoutingRule     routing,
            SessionPolicy   sessionPolicy,
            Integer         priority,
            BindingStatus   status,
            Long            authResolverId,
            OnUnresolvedPolicy onUnresolved,
            List<String>    allowedOverrideProfiles,
            OnOverrideDenied onOverrideDenied,
            String          unresolvedReplyText
    ) {
        public BindingUpdate(String profileId, Integer profileVersion, RoutingRule routing,
                             SessionPolicy sessionPolicy, Integer priority, BindingStatus status,
                             Long authResolverId, OnUnresolvedPolicy onUnresolved,
                             List<String> allowedOverrideProfiles, OnOverrideDenied onOverrideDenied) {
            this(profileId, profileVersion, routing, sessionPolicy, priority, status, authResolverId,
                    onUnresolved, allowedOverrideProfiles, onOverrideDenied, null);
        }
    }

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
            Instant         deactivatedAt,
            Long            authResolverId,
            OnUnresolvedPolicy onUnresolved,
            List<String>    allowedOverrideProfiles,
            OnOverrideDenied onOverrideDenied,
            String          unresolvedReplyText
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
                    b.deactivatedAt(),
                    b.authResolverId(),
                    b.onUnresolved(),
                    b.allowedOverrideProfiles().stream().sorted().toList(),
                    b.onOverrideDenied(),
                    b.unresolvedReplyText());
        }
    }
}
