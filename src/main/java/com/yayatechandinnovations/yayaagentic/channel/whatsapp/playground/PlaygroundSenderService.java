package com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.channel.ChannelEndpointRef;
import com.yayatechandinnovations.yayaagentic.channel.binding.BindingStatus;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBinding;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBindingService;
import com.yayatechandinnovations.yayaagentic.channel.binding.RoutingRule;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.BusinessProfile;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.OnboardingState;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderStatus;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.config.SecretCipher;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lazily provisions the per-workspace playground sender. The row exists in
 * {@code whatsapp_senders} with {@code status=ACTIVE} so it routes through
 * the same {@code ChannelProfileResolver} → {@code InboundDispatchWorker} →
 * {@code OutboundDispatcher} path as a real sender — the
 * {@link com.yayatechandinnovations.yayaagentic.channel.whatsapp.OutboundDispatcher}
 * checks {@link PlaygroundSenders#isPlayground} at the dispatch site to
 * skip Twilio.
 */
@Service
public class PlaygroundSenderService {

    private static final Logger log = LoggerFactory.getLogger(PlaygroundSenderService.class);
    private static final String PLAYGROUND_WEBHOOK_SECRET = "playground-sim-no-secret";

    private final WhatsAppSenderRepository repo;
    private final ChannelBindingService bindings;
    private final SecretCipher cipher;
    private final ObjectMapper json;

    public PlaygroundSenderService(WhatsAppSenderRepository repo,
                                   ChannelBindingService bindings,
                                   SecretCipher cipher,
                                   ObjectMapper json) {
        this.repo = repo;
        this.bindings = bindings;
        this.cipher = cipher;
        this.json = json;
    }

    /**
     * Return the playground sender for a tenant, creating it on the fly if
     * absent. Idempotent under concurrent first-call.
     */
    @Transactional
    public SenderId ensureForTenant(Ids.TenantId tenant) {
        SenderId id = PlaygroundSenders.forTenant(tenant);
        if (repo.existsById(id.value())) return id;

        WhatsAppSenderEntity entity = new WhatsAppSenderEntity(
                id.value(),
                tenant.value(),
                PlaygroundSenders.phoneForTenant(tenant),
                "Playground (simulated)",
                writeJson(playgroundBusinessProfile()),
                SenderStatus.ACTIVE.name(),
                cipher.encrypt(PLAYGROUND_WEBHOOK_SECRET));
        entity.setOnboardingStateJson(writeJson(OnboardingState.empty()));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("playground", true);
        entity.setMetadataJson(writeJson(metadata));
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        try {
            repo.save(entity);
        } catch (DataIntegrityViolationException raceLost) {
            log.debug("Playground sender for {} created concurrently — using existing",
                    tenant.value());
        }
        return id;
    }

    /** Ensure the playground sender is bound to the chosen profile. If a
     *  binding already exists pointing at a different profile, retarget it.
     *  Idempotent and lightweight — the playground UX repoints on every
     *  profile change. */
    @Transactional
    public ChannelBinding ensureBinding(Ids.TenantId tenant, SenderId sender,
                                        Ids.ProfileId profile) {
        ChannelEndpointRef endpoint = new ChannelEndpointRef.WhatsAppSenderRef(sender);
        List<ChannelBinding> existing = bindings.listForTenant(tenant).stream()
                .filter(b -> b.endpoint().externalId().equals(sender.value())
                          && b.endpoint().kindName().equals(endpoint.kindName()))
                .toList();
        if (existing.isEmpty()) {
            return bindings.create(tenant, endpoint, profile,
                    Optional.of(profile.version()),
                    new RoutingRule.MatchAny(),
                    SessionPolicy.defaults(),
                    100,
                    null,
                    com.yayatechandinnovations.yayaagentic.channel.binding.OnUnresolvedPolicy.ANONYMOUS);
        }
        ChannelBinding current = existing.get(0);
        if (current.profile().equals(profile) && current.status() == BindingStatus.ACTIVE) {
            return current;
        }
        return bindings.update(tenant, current.id(), profile,
                Optional.of(profile.version()),
                new RoutingRule.MatchAny(),
                SessionPolicy.defaults(),
                100,
                BindingStatus.ACTIVE,
                current.authResolverId(),
                current.onUnresolved());
    }

    private static BusinessProfile playgroundBusinessProfile() {
        return new BusinessProfile(
                "Playground (simulated)",
                "Playground",
                "playground@yaya.local",
                "(simulated)",
                "https://yaya.local",
                "PROFESSIONAL_SERVICES",
                "In-app simulated sender for testing profiles without Twilio");
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not encode " + value.getClass().getSimpleName(), e);
        }
    }
}
