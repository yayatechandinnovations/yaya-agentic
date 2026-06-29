package com.yayatechandinnovations.yayaagentic.channel.chat;

import com.yayatechandinnovations.yayaagentic.auth.anon.AnonymousAuthenticator;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-domain checks for the chat channel — the entry point state machine, auth
 * mode semantics, abuse-policy defaults, and the anonymous principal shape
 * ({@code chat-channel-design.md} §6, §7.1, §8.1). No DB / Spring context.
 */
class ChatEntryPointDomainTest {

    @Test
    void status_transitions_follow_the_lifecycle() {
        assertThat(EntryPointStatus.DRAFT.canTransitionTo(EntryPointStatus.ACTIVE)).isTrue();
        assertThat(EntryPointStatus.ACTIVE.canTransitionTo(EntryPointStatus.PAUSED)).isTrue();
        assertThat(EntryPointStatus.PAUSED.canTransitionTo(EntryPointStatus.ACTIVE)).isTrue();
        assertThat(EntryPointStatus.ACTIVE.canTransitionTo(EntryPointStatus.DEACTIVATED)).isTrue();

        // DEACTIVATED is terminal; DRAFT cannot jump straight to PAUSED.
        assertThat(EntryPointStatus.DEACTIVATED.canTransitionTo(EntryPointStatus.ACTIVE)).isFalse();
        assertThat(EntryPointStatus.DRAFT.canTransitionTo(EntryPointStatus.PAUSED)).isFalse();
    }

    @Test
    void only_active_serves_sessions() {
        assertThat(EntryPointStatus.ACTIVE.servesSessions()).isTrue();
        assertThat(EntryPointStatus.PAUSED.servesSessions()).isFalse();
        assertThat(EntryPointStatus.DRAFT.servesSessions()).isFalse();
        assertThat(EntryPointStatus.DEACTIVATED.servesSessions()).isFalse();
    }

    @Test
    void auth_mode_semantics() {
        assertThat(AuthMode.AUTH_REQUIRED.requiresTokenToStart()).isTrue();
        assertThat(AuthMode.ANONYMOUS_ONLY.requiresTokenToStart()).isFalse();
        assertThat(AuthMode.AUTH_OPTIONAL.requiresTokenToStart()).isFalse();

        assertThat(AuthMode.ANONYMOUS_ONLY.allowsAnonymousStart()).isTrue();
        assertThat(AuthMode.AUTH_OPTIONAL.allowsAnonymousStart()).isTrue();
        assertThat(AuthMode.AUTH_REQUIRED.allowsAnonymousStart()).isFalse();

        assertThat(AuthMode.parse(null)).isEqualTo(AuthMode.ANONYMOUS_ONLY);
        assertThat(AuthMode.parse("auth_required")).isEqualTo(AuthMode.AUTH_REQUIRED);
    }

    @Test
    void abuse_policy_supplies_sane_defaults_for_empty_values() {
        AbusePolicy d = AbusePolicy.defaults();
        assertThat(d.startsPerDevicePerHour()).isPositive();
        assertThat(d.anonymousDailyMessageCap()).isPositive();
        assertThat(d.challenge()).isEqualTo(AbusePolicy.Challenge.NONE);

        // A record with zero/null fields heals to defaults (deserialized empty {}).
        AbusePolicy healed = new AbusePolicy(0, 0, 0, 0, null);
        assertThat(healed.startsPerDevicePerHour()).isEqualTo(d.startsPerDevicePerHour());
        assertThat(healed.challenge()).isEqualTo(AbusePolicy.Challenge.NONE);
    }

    @Test
    void anonymous_principal_is_minimal_scope_and_handle_scoped() {
        AnonymousAuthenticator anon = new AnonymousAuthenticator();
        Principal p = anon.mintAnonymous(new Ids.TenantId("t1"), "e_abc", "dev-9");

        assertThat(p.subject()).isEqualTo("chat:e_abc:anon:dev-9");
        assertThat(p.scopes()).containsExactlyInAnyOrder("channel:chat", "channel:read");
        assertThat(p.claims()).containsEntry("anon", true).containsEntry("entryPoint", "e_abc");
        assertThat(p.tenant().value()).isEqualTo("t1");
    }

    @Test
    void anonymous_principal_tolerates_missing_device_id() {
        Principal p = new AnonymousAuthenticator().mintAnonymous(new Ids.TenantId("t1"), "e_abc", null);
        assertThat(p.subject()).isEqualTo("chat:e_abc:anon:nodev");
    }
}
