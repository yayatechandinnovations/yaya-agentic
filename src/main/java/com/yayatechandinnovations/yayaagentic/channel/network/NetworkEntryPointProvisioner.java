package com.yayatechandinnovations.yayaagentic.channel.network;

import com.yayatechandinnovations.yayaagentic.channel.chat.AuthConfig;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPoint;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointService;
import com.yayatechandinnovations.yayaagentic.channel.chat.EntryPointStatus;
import com.yayatechandinnovations.yayaagentic.channel.chat.AuthMode;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.engine.bootstrap.NetworkPlayerProfileBootstrap;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Auto-provisions the Corillo chat front door for {@code player_network_v1} so an
 * operator never has to hand-create one — the "master switch" behind the network
 * settings' {@code enabled} flag (enablement UX).
 *
 * <p>The entry point uses {@code AuthConfig.None()} + {@code requiresVerifiedPhone}
 * — NO auth resolver is needed: the existing chat phone-verify device pass stamps
 * the {@code phone} claim the network tools read. Idempotent.
 */
@Component
public class NetworkEntryPointProvisioner {

    private static final String PROFILE_ID = NetworkPlayerProfileBootstrap.PLAYER_NETWORK_PROFILE.value();
    // Non-blank placeholder; an operator can rebrand later via the entry-point UI.
    private static final String PLACEHOLDER_LOGO =
            "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg'/%3E";

    private final ChatEntryPointService entryPoints;

    public NetworkEntryPointProvisioner(ChatEntryPointService entryPoints) {
        this.entryPoints = entryPoints;
    }

    /** Ensure an ACTIVE network entry point exists for the tenant; return it. */
    public ChatEntryPoint ensure(TenantId tenant) {
        Optional<ChatEntryPoint> existing = find(tenant);
        if (existing.isPresent()) {
            ChatEntryPoint ep = existing.get();
            return ep.status() == EntryPointStatus.ACTIVE
                    ? ep : entryPoints.publish(tenant, ep.id());
        }
        ChatEntryPoint created = entryPoints.createDraft(tenant, new ChatEntryPointService.CreateCommand(
                PROFILE_ID, 1, "Corillo", PLACEHOLDER_LOGO,
                null, "¡Hola! Soy tu asistente de la red Corillo.", "es", null,
                /* discoverable */ Boolean.FALSE,
                AuthMode.AUTH_OPTIONAL, new AuthConfig.None(), null,
                /* requiresVerifiedPhone */ Boolean.TRUE,
                java.util.Set.of(), null));
        return entryPoints.publish(tenant, created.id());
    }

    /** Pause the network entry point (front door off) if one exists. */
    public void pauseIfPresent(TenantId tenant) {
        find(tenant).filter(ep -> ep.status() == EntryPointStatus.ACTIVE)
                .ifPresent(ep -> entryPoints.pause(tenant, ep.id()));
    }

    public Optional<ChatEntryPoint> find(TenantId tenant) {
        return entryPoints.listForTenant(tenant).stream()
                .filter(ep -> PROFILE_ID.equals(ep.profile().value())
                        && ep.status() != EntryPointStatus.DEACTIVATED)
                .findFirst();
    }
}
