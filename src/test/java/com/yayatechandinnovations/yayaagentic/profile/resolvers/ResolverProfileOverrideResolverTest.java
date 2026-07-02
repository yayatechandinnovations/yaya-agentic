package com.yayatechandinnovations.yayaagentic.profile.resolvers;

import com.yayatechandinnovations.yayaagentic.channel.auth.ChannelAuthIntegrator;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ResolverProfileOverrideResolverTest {

    private final ResolverProfileOverrideResolver resolver = new ResolverProfileOverrideResolver();

    @Test
    void reads_the_reserved_profile_claim_as_latest_when_unpinned() {
        Optional<Ids.ProfileId> out = resolver.resolve(
                req(), null,
                principal(Map.of(ChannelAuthIntegrator.RESERVED_PROFILE_CLAIM, "retail-vip")));

        assertThat(out).isPresent();
        assertThat(out.get().value()).isEqualTo("retail-vip");
        assertThat(out.get().isLatest()).isTrue();
    }

    @Test
    void pins_the_version_when_present() {
        Optional<Ids.ProfileId> out = resolver.resolve(
                req(), null,
                principal(Map.of(
                        ChannelAuthIntegrator.RESERVED_PROFILE_CLAIM, "retail-vip",
                        ChannelAuthIntegrator.RESERVED_PROFILE_VERSION_CLAIM, 3)));

        assertThat(out).isPresent();
        assertThat(out.get().value()).isEqualTo("retail-vip");
        assertThat(out.get().isLatest()).isFalse();
        assertThat(out.get().version()).isEqualTo(3);
    }

    @Test
    void yields_when_no_override_claim_is_present() {
        assertThat(resolver.resolve(req(), null, principal(Map.of("role", "vip")))).isEmpty();
    }

    @Test
    void yields_when_principal_is_null() {
        assertThat(resolver.resolve(req(), null, null)).isEmpty();
    }

    private static Principal principal(Map<String, Object> claims) {
        return new Principal("u_1", new Ids.TenantId("acme"), Set.of(), claims, Instant.now());
    }

    private static StartConversationRequest req() {
        return new StartConversationRequest(new Ids.TenantId("acme"),
                Optional.empty(), "whatsapp", Map.of());
    }
}
