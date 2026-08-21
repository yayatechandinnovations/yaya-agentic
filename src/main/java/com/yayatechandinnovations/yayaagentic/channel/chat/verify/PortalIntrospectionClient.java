package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

import com.yayatechandinnovations.yayaagentic.config.TenantNetworkSettingsService;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Server-side introspection of a hub portal session for the chat
 * portal-exchange ({@code corillo-chat-portal-session-exchange-design.md}):
 * the tenant's hub already phone-OTP-verified the caller at portal login, so
 * presenting that portal JWT to the hub's own {@code GET /api/portal/me} is an
 * attestation of the phone — good enough to mint a device pass without a
 * second SMS. The hub base URL is resolved per tenant exactly like the broker
 * plane ({@code HubBrokerClient}); no extra credential is needed because the
 * portal JWT itself is what the hub authenticates.
 *
 * <p>The phone is taken from the hub's response, never from the caller, so a
 * stolen portal token can only ever mint a pass for the phone the hub already
 * verified for that session. Any failure — bad token, unreachable hub,
 * missing phone — collapses to {@code Optional.empty()} and the caller fails
 * closed into the normal OTP flow.
 */
@Component
public class PortalIntrospectionClient {

    private static final Logger log = LoggerFactory.getLogger(PortalIntrospectionClient.class);

    /** The hub-verified identity behind a live portal session. */
    public record PortalIdentity(String phoneE164, String name) {}

    private final WebClient.Builder webClientBuilder;
    private final TenantNetworkSettingsService settings;
    private final YayaAgenticProperties props;

    public PortalIntrospectionClient(WebClient.Builder webClientBuilder,
                                     TenantNetworkSettingsService settings,
                                     YayaAgenticProperties props) {
        this.webClientBuilder = webClientBuilder;
        this.settings = settings;
        this.props = props;
    }

    public Optional<PortalIdentity> introspect(Ids.TenantId tenant, String portalToken) {
        if (portalToken == null || portalToken.isBlank()) return Optional.empty();
        String base = settings.settings(tenant).hubBaseUrl();
        if (base == null || base.isBlank()) {
            log.warn("[chat-verify] hub base-url not configured for tenant={}; refusing portal exchange",
                    tenant.value());
            return Optional.empty();
        }
        try {
            Map<?, ?> profile = webClientBuilder.baseUrl(base).build().get()
                    .uri("/api/portal/me")
                    .header("Authorization", "Bearer " + portalToken.trim())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block(timeout());
            if (profile == null) return Optional.empty();
            String phone = str(profile.get("phone"));
            if (phone == null || phone.isBlank()) return Optional.empty();
            return Optional.of(new PortalIdentity(phone.trim(), displayName(profile)));
        } catch (WebClientResponseException e) {
            log.info("[chat-verify] portal introspection rejected tenant={} status={}",
                    tenant.value(), e.getStatusCode().value());
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("[chat-verify] portal introspection failed tenant={} err={}",
                    tenant.value(), e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Nickname first (what the player chose to be called), else first+last. */
    private static String displayName(Map<?, ?> profile) {
        String nickname = str(profile.get("nickname"));
        if (nickname != null && !nickname.isBlank()) return nickname.trim();
        String first = str(profile.get("firstName"));
        String last = str(profile.get("lastName"));
        String full = ((first == null ? "" : first.trim()) + " "
                + (last == null ? "" : last.trim())).trim();
        return full.isBlank() ? null : full;
    }

    private Duration timeout() {
        return props.network().hub() == null ? Duration.ofSeconds(10) : props.network().hub().timeout();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
