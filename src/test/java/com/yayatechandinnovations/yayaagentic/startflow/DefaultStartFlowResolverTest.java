package com.yayatechandinnovations.yayaagentic.startflow;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.profile.Profile;
import com.yayatechandinnovations.yayaagentic.profile.StartConversationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Precedence: channel binding (hint) → profile default → none. */
class DefaultStartFlowResolverTest {

    private final DefaultStartFlowResolver resolver = new DefaultStartFlowResolver();
    private final Ids.TenantId tenant = new Ids.TenantId("acme");

    private StartConversationRequest req(Map<String, Object> hints) {
        return new StartConversationRequest(tenant, Optional.empty(), "chat", hints);
    }

    private Profile profile(Ids.StartFlowId defaultFlow) {
        return new Profile(new Ids.ProfileId("p", 1), tenant, "P", "hi", "sp",
                List.of(), List.of(), null, "en", Map.of(), null, defaultFlow);
    }

    @Test
    void channel_binding_wins_over_profile_default() {
        var out = resolver.resolve(
                req(Map.of("channel.startFlowId", "channel-flow")),
                profile(Ids.StartFlowId.latest("profile-flow")), null);
        assertThat(out).map(Ids.StartFlowId::value).hasValue("channel-flow");
        assertThat(out).get().extracting(Ids.StartFlowId::isLatest).isEqualTo(true);
    }

    @Test
    void channel_binding_can_pin_a_version() {
        var out = resolver.resolve(
                req(Map.of("channel.startFlowId", "channel-flow", "channel.startFlowVersion", "3")),
                profile(null), null);
        assertThat(out).get().extracting(Ids.StartFlowId::value).isEqualTo("channel-flow");
        assertThat(out).get().extracting(Ids.StartFlowId::version).isEqualTo(3);
    }

    @Test
    void falls_back_to_profile_default() {
        var out = resolver.resolve(req(Map.of()), profile(Ids.StartFlowId.latest("profile-flow")), null);
        assertThat(out).map(Ids.StartFlowId::value).hasValue("profile-flow");
    }

    @Test
    void empty_when_no_binding_anywhere() {
        assertThat(resolver.resolve(req(Map.of()), profile(null), null)).isEmpty();
    }

    @Test
    void legacy_flat_hint_key_is_honored() {
        var out = resolver.resolve(req(Map.of("startFlowId", "flat")), profile(null), null);
        assertThat(out).map(Ids.StartFlowId::value).hasValue("flat");
    }
}
