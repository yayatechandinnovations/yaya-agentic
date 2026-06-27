package com.yayatechandinnovations.yayaagentic.channel.binding;

import com.yayatechandinnovations.yayaagentic.channel.ChannelEndpointRef;
import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.Instant;
import java.util.Optional;

/**
 * One row of {@code channel_bindings}. Built by
 * {@link ChannelBindingService} from the persistence-layer entity; never
 * leaked from the service.
 *
 * <p>See {@code whatsapp-channel-design.md} §8 (binding shape) and
 * {@code docs/design/channel-auth-resolver-design.md} §7
 * ({@code authResolverId} + {@code onUnresolved}).
 *
 * @param id             the binding row id; null when transient
 * @param tenant         owning tenant
 * @param endpoint       the channel endpoint this binding routes from
 * @param profile        the profile this binding routes to
 * @param profileVersion empty = "always the ACTIVE version" (pinned otherwise)
 * @param routing        the match rule
 * @param sessionPolicy  per-binding session knobs
 * @param priority       lower = checked first (§8.1)
 * @param status         {@code ACTIVE | PAUSED | DEACTIVATED}
 * @param authResolverId optional FK to {@code channel_auth_resolvers.id}.
 *                       Null = "FAQ mode": the inbound dispatch worker
 *                       skips identity resolution and uses the baseline
 *                       {@code whatsapp:+E164} Principal directly.
 * @param onUnresolved   what to do when the resolver returns empty (404
 *                       from the tenant's webhook). {@code REJECT} drops
 *                       the message; {@code ANONYMOUS} falls through to
 *                       the baseline Principal.
 */
public record ChannelBinding(
        BindingId             id,
        Ids.TenantId          tenant,
        ChannelEndpointRef    endpoint,
        Ids.ProfileId         profile,
        Optional<Integer>     profileVersion,
        RoutingRule           routing,
        SessionPolicy         sessionPolicy,
        int                   priority,
        BindingStatus         status,
        Instant               createdAt,
        Instant               deactivatedAt,
        Long                  authResolverId,
        OnUnresolvedPolicy    onUnresolved
) {

    public ChannelBinding {
        if (tenant == null)        throw new IllegalArgumentException("tenant required");
        if (endpoint == null)      throw new IllegalArgumentException("endpoint required");
        if (profile == null)       throw new IllegalArgumentException("profile required");
        if (routing == null)       throw new IllegalArgumentException("routing required");
        if (sessionPolicy == null) throw new IllegalArgumentException("sessionPolicy required");
        if (status == null)        throw new IllegalArgumentException("status required");
        profileVersion = profileVersion == null ? Optional.empty() : profileVersion;
        onUnresolved = onUnresolved == null ? OnUnresolvedPolicy.REJECT : onUnresolved;
    }
}
