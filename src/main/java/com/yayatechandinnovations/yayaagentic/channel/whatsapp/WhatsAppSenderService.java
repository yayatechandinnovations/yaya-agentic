package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.channel.MessagingChannelProvider;
import com.yayatechandinnovations.yayaagentic.channel.ProviderCredsResolver;
import com.yayatechandinnovations.yayaagentic.channel.SenderRef;
import com.yayatechandinnovations.yayaagentic.channel.SenderRegistrationRequest;
import com.yayatechandinnovations.yayaagentic.channel.SenderRegistrationResult;
import com.yayatechandinnovations.yayaagentic.channel.SenderSyncResult;
import com.yayatechandinnovations.yayaagentic.channel.ChannelKind;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioMessagingChannelProvider;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioProperties;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.operator_auth.config.SecretCipher;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Sender CRUD + state-machine arbiter. The controller is a thin REST surface
 * over this; the W2 poller (when it lands) and the W4 inbound dispatcher
 * also drive transitions through these methods, never through the
 * repository directly.
 *
 * <p>v1 ({@code WhatsAppSenderService}) intentionally stops short of the
 * actual Twilio submit — {@link #submit} returns the sender in
 * {@code SUBMITTING} only after W2 wires the
 * {@code MessagingChannelProvider.registerSender} call. For now the method
 * exists on the surface (controllers can wire it) and throws
 * {@code not_implemented} when invoked — a clean seam, not a missing API.
 */
@Service
public class WhatsAppSenderService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppSenderService.class);
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{6,17}$");
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {};
    private static final String INBOUND_WEBHOOK_PATH    = "/v1/channels/whatsapp/twilio/webhook/";
    private static final String STATUS_CALLBACK_PATH    = "/v1/channels/whatsapp/twilio/status-callback/";

    private final WhatsAppSenderRepository repo;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final TwilioMessagingChannelProvider twilio;
    private final ProviderCredsResolver credsResolver;
    private final TwilioProperties twilioProps;
    private final YayaAgenticProperties yayaProps;
    private final ApplicationEventPublisher events;
    private final SecureRandom random = new SecureRandom();

    public WhatsAppSenderService(WhatsAppSenderRepository repo,
                                 SecretCipher cipher,
                                 ObjectMapper json,
                                 TwilioMessagingChannelProvider twilio,
                                 ProviderCredsResolver credsResolver,
                                 TwilioProperties twilioProps,
                                 YayaAgenticProperties yayaProps,
                                 ApplicationEventPublisher events) {
        this.repo = repo;
        this.cipher = cipher;
        this.json = json;
        this.twilio = twilio;
        this.credsResolver = credsResolver;
        this.twilioProps = twilioProps;
        this.yayaProps = yayaProps;
        this.events = events;
    }

    /* ---------------------------- read ------------------------------------ */

    public List<WhatsAppSender> listForTenant(Ids.TenantId tenant) {
        return repo.findByTenantIdOrderByCreatedAtDesc(tenant.value()).stream()
                .map(this::toDomain)
                .toList();
    }

    public WhatsAppSender requireForTenant(Ids.TenantId tenant, SenderId id) {
        return repo.findByTenantIdAndId(tenant.value(), id.value())
                .map(this::toDomain)
                .orElseThrow(() -> AdminApiException.notFound(
                        "sender_not_found",
                        "no such WhatsApp sender '" + id.value() + "' in tenant '" + tenant.value() + "'"));
    }

    /** Cross-tenant lookup used by the webhook controller to resolve which
     *  tenant a sender belongs to before tenant-scoped access. The phone
     *  number is the trust root — the path-segment senderId is just a
     *  routing hint — but this lookup is the bridge from segment to tenant. */
    public Optional<Ids.TenantId> findTenantForSenderId(SenderId id) {
        return repo.findById(id.value()).map(e -> new Ids.TenantId(e.getTenantId()));
    }

    /* ---------------------------- create ---------------------------------- */

    @Transactional
    public WhatsAppSender createDraft(Ids.TenantId tenant,
                                      String phoneE164,
                                      String displayName,
                                      BusinessProfile business,
                                      Optional<String> defaultLocale) {
        validatePhone(phoneE164);
        validateDisplayName(displayName);

        if (phoneE164.startsWith("+155501")) {
            throw AdminApiException.badRequest("reserved_phone",
                    "phone numbers in +1 555 010X XXXX are reserved for the platform's simulated playground sender");
        }

        repo.findByPhoneE164(phoneE164).ifPresent(existing -> {
            throw AdminApiException.conflict("phone_in_use",
                    "phone number " + phoneE164 + " is already registered to another sender");
        });

        SenderId id = mintSenderId();
        String webhookSecret = mintWebhookSecret();
        byte[] webhookSecretEnc = cipher.encrypt(webhookSecret);
        if (webhookSecretEnc == null) {
            throw new IllegalStateException("SecretCipher returned null for a non-empty plaintext");
        }

        WhatsAppSenderEntity entity = new WhatsAppSenderEntity(
                id.value(),
                tenant.value(),
                phoneE164,
                displayName,
                writeJson(business),
                SenderStatus.DRAFT.name(),
                webhookSecretEnc);
        entity.setDefaultLocale(defaultLocale.filter(s -> !s.isBlank()).orElse("en-US"));
        entity.setOnboardingStateJson(writeJson(OnboardingState.empty()));
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        try {
            return toDomain(repo.save(entity));
        } catch (DataIntegrityViolationException dup) {
            throw AdminApiException.conflict("phone_in_use",
                    "phone number " + phoneE164 + " is already registered to another sender");
        }
    }

    /* ---------------------------- edit DRAFT/FAILED ----------------------- */

    @Transactional
    public WhatsAppSender editDraft(Ids.TenantId tenant,
                                    SenderId id,
                                    String phoneE164,
                                    String displayName,
                                    BusinessProfile business,
                                    Optional<String> defaultLocale) {
        WhatsAppSenderEntity entity = loadEntity(tenant, id);
        SenderStatus status = SenderStatus.valueOf(entity.getStatus());
        if (!status.isEditable()) {
            throw AdminApiException.conflict("not_editable",
                    "sender in status " + status + " cannot be edited — only DRAFT or FAILED are editable");
        }

        validatePhone(phoneE164);
        validateDisplayName(displayName);

        if (!entity.getPhoneE164().equals(phoneE164)) {
            repo.findByPhoneE164(phoneE164)
                    .filter(other -> !other.getId().equals(entity.getId()))
                    .ifPresent(other -> {
                        throw AdminApiException.conflict("phone_in_use",
                                "phone number " + phoneE164 + " is already registered to another sender");
                    });
            entity.setPhoneE164(phoneE164);
        }
        entity.setDisplayName(displayName);
        entity.setBusinessProfileJson(writeJson(business));
        defaultLocale.filter(s -> !s.isBlank()).ifPresent(entity::setDefaultLocale);

        // FAILED → DRAFT on edit, per §6.1 last paragraph.
        if (status == SenderStatus.FAILED) {
            entity.setStatus(SenderStatus.DRAFT.name());
        }
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return toDomain(repo.save(entity));
    }

    /* ---------------------------- state transitions ----------------------- */

    /**
     * Submit the sender to the BSP. Splits into two transactions so the slow
     * Twilio call doesn't hold the row lock — first commit moves DRAFT/FAILED
     * → SUBMITTING; on the Twilio result the second commit lands the verdict.
     *
     * <p>Outcomes per {@link SenderRegistrationResult}:
     * <ul>
     *   <li>{@code Accepted}        → SUBMITTING → PENDING_REVIEW + persist
     *       Twilio sender SID</li>
     *   <li>{@code Rejected}        → SUBMITTING → FAILED + persist failure
     *       message (operator edits, resubmits)</li>
     *   <li>{@code TransientFailure} → leave as SUBMITTING; the poller will
     *       retry the registration on its next pass</li>
     * </ul>
     */
    public WhatsAppSender submit(Ids.TenantId tenant, SenderId id) {
        if (!credsResolver.isReady()) {
            throw AdminApiException.conflict("provider_not_ready",
                    "WhatsApp provisioning is not configured on this instance.");
        }

        // tx 1 — DRAFT/FAILED → SUBMITTING, all in one short DB round trip
        WhatsAppSender pending = beginSubmit(tenant, id);

        // outside tx — call Twilio
        SenderRef refForRetry = pending.onboarding().twilioSenderSid() == null
                ? null
                : new SenderRef(twilio.id(), pending.onboarding().twilioSenderSid());

        SenderRegistrationRequest req = buildRegistrationRequest(pending);
        SenderRegistrationResult result;
        try {
            result = twilio.registerSender(req, credsResolver.resolve(pending));
        } catch (RuntimeException e) {
            log.warn("Twilio registerSender threw for sender {}: {}", id.value(), e.getMessage());
            result = new SenderRegistrationResult.TransientFailure("transport_error", e.getMessage());
        }

        // tx 2 — fold the verdict back into the row
        return finishSubmit(tenant, id, result, refForRetry);
    }

    @Transactional
    protected WhatsAppSender beginSubmit(Ids.TenantId tenant, SenderId id) {
        WhatsAppSenderEntity entity = loadEntity(tenant, id);
        requireTransition(entity, SenderStatus.SUBMITTING);
        SenderStatus from = SenderStatus.valueOf(entity.getStatus());
        entity.setStatus(SenderStatus.SUBMITTING.name());
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        WhatsAppSender saved = toDomain(repo.save(entity));
        publishStatusChange(saved, from, SenderStatus.SUBMITTING, "submit_started");
        return saved;
    }

    @Transactional
    protected WhatsAppSender finishSubmit(Ids.TenantId tenant, SenderId id,
                                          SenderRegistrationResult result,
                                          SenderRef refForRetry) {
        WhatsAppSenderEntity entity = loadEntity(tenant, id);
        SenderStatus from = SenderStatus.valueOf(entity.getStatus());

        switch (result) {
            case SenderRegistrationResult.Accepted acc -> {
                entity.setStatus(SenderStatus.PENDING_REVIEW.name());
                entity.setTwilioSenderSid(acc.senderRef().externalId());
                entity.setOnboardingStateJson(writeJson(new OnboardingState(
                        acc.senderRef().externalId(), acc.bspStatus(),
                        null, null, Instant.now(), 0)));
            }
            case SenderRegistrationResult.Rejected rej -> {
                entity.setStatus(SenderStatus.FAILED.name());
                entity.setOnboardingStateJson(writeJson(new OnboardingState(
                        entity.getTwilioSenderSid(), "REJECTED",
                        rej.code(), rej.message(), Instant.now(), 1)));
            }
            case SenderRegistrationResult.TransientFailure t -> {
                // Stay in SUBMITTING so the poller picks it up.
                entity.setOnboardingStateJson(writeJson(new OnboardingState(
                        refForRetry == null ? null : refForRetry.externalId(),
                        "RETRY_PENDING", t.code(), t.message(), Instant.now(), 1)));
            }
        }
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setLastSyncAt(OffsetDateTime.now(ZoneOffset.UTC));

        WhatsAppSender saved = toDomain(repo.save(entity));
        SenderStatus to = SenderStatus.valueOf(entity.getStatus());
        if (to != from) {
            publishStatusChange(saved, from, to, "submit_" + result.getClass().getSimpleName());
        }
        return saved;
    }

    /**
     * Force a fresh BSP sync for one sender — POST {@code .../sync}. Same
     * code path as the scheduled poller, just narrowed to a single ID and
     * runnable on demand from the admin UI.
     */
    public WhatsAppSender forceSync(Ids.TenantId tenant, SenderId id) {
        WhatsAppSender sender = requireForTenant(tenant, id);
        if (sender.onboarding().twilioSenderSid() == null) {
            throw AdminApiException.conflict("not_submitted",
                    "sender has not been submitted to WhatsApp yet — there is nothing to sync");
        }
        if (!credsResolver.isReady()) {
            throw AdminApiException.conflict("provider_not_ready",
                    "WhatsApp provisioning is not configured on this instance.");
        }
        return syncOne(sender);
    }

    /** Driven by both forceSync (synchronous) and SenderSyncJob (scheduled). */
    public WhatsAppSender syncOne(WhatsAppSender sender) {
        SenderRef ref = new SenderRef(twilio.id(), sender.onboarding().twilioSenderSid());
        SenderSyncResult syncResult = twilio.syncSender(ref, credsResolver.resolve(sender));
        return applySyncResult(sender.tenant(), sender.id(), syncResult);
    }

    @Transactional
    protected WhatsAppSender applySyncResult(Ids.TenantId tenant, SenderId id,
                                             SenderSyncResult syncResult) {
        WhatsAppSenderEntity entity = loadEntity(tenant, id);
        SenderStatus from = SenderStatus.valueOf(entity.getStatus());
        SenderStatus next = mapBspStatusToInternal(from, syncResult);

        if (next != from) {
            entity.setStatus(next.name());
        }
        if (next == SenderStatus.ACTIVE && syncResult.approvedDisplayName() != null) {
            entity.setApprovedDisplayName(syncResult.approvedDisplayName());
        }
        OnboardingState onboarding = new OnboardingState(
                syncResult.senderRef().externalId(),
                syncResult.bspStatus(),
                syncResult.lastFailureCode(),
                syncResult.lastFailureMessage(),
                syncResult.polledAt(),
                bumpAttempts(entity)
        );
        entity.setOnboardingStateJson(writeJson(onboarding));
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setLastSyncAt(OffsetDateTime.now(ZoneOffset.UTC));
        WhatsAppSender saved = toDomain(repo.save(entity));
        if (next != from) {
            publishStatusChange(saved, from, next, "bsp_sync_" + syncResult.bspStatus());
        }
        return saved;
    }

    /** Senders the poller should sweep — currently in flight at the BSP. */
    public List<WhatsAppSender> findInFlight() {
        return repo.findByStatusIn(List.of(
                SenderStatus.SUBMITTING.name(),
                SenderStatus.PENDING_REVIEW.name()))
                .stream()
                .filter(e -> e.getTwilioSenderSid() != null)
                .map(this::toDomain)
                .toList();
    }

    @Transactional
    public WhatsAppSender suspend(Ids.TenantId tenant, SenderId id) {
        return transition(tenant, id, SenderStatus.SUSPENDED, "operator_suspend");
    }

    @Transactional
    public WhatsAppSender resume(Ids.TenantId tenant, SenderId id) {
        return transition(tenant, id, SenderStatus.ACTIVE, "operator_resume");
    }

    public WhatsAppSender revoke(Ids.TenantId tenant, SenderId id) {
        // Revoke calls Twilio outside the DB transaction — same reasoning as
        // submit: don't hold the row lock for a remote call.
        WhatsAppSender sender = requireForTenant(tenant, id);
        String externalSid = sender.onboarding().twilioSenderSid();
        if (externalSid != null && credsResolver.isReady()) {
            try {
                twilio.revokeSender(
                        new SenderRef(twilio.id(), externalSid),
                        credsResolver.resolve(sender));
            } catch (RuntimeException e) {
                // We still flip the row to REVOKED — the platform stops
                // listening to the sender, which is the operator's intent.
                // The BSP-side cleanup is best-effort; an operator-tier
                // dashboard can replay revoke for any rows that didn't take.
                log.warn("Twilio revoke failed for sender {} (still flipping to REVOKED locally): {}",
                        id.value(), e.getMessage());
            }
        }
        return transition(tenant, id, SenderStatus.REVOKED, "operator_revoke");
    }

    /* ---------------------------- helpers --------------------------------- */

    private SenderRegistrationRequest buildRegistrationRequest(WhatsAppSender sender) {
        Map<String, Object> payload = new LinkedHashMap<>();
        BusinessProfile b = sender.business();
        payload.put("legalName", b.legalName());
        payload.put("contactEmail", b.contactEmail());
        payload.put("address", b.address() == null ? "" : b.address());
        payload.put("websiteUrl", b.websiteUrl() == null ? "" : b.websiteUrl());
        payload.put("vertical", b.vertical());
        payload.put("about", b.about() == null ? "" : b.about());

        String hostBase = twilioProps.webhookBaseUrl() == null || twilioProps.webhookBaseUrl().isBlank()
                ? yayaProps.publicBaseUrl()
                : twilioProps.webhookBaseUrl();
        String webhookUrl       = hostBase + INBOUND_WEBHOOK_PATH + sender.id().value();
        String statusCallbackUrl = hostBase + STATUS_CALLBACK_PATH + sender.id().value();

        return new SenderRegistrationRequest(
                new ChannelKind.WhatsApp(),
                sender.phoneE164(),
                sender.displayName(),
                webhookUrl,
                statusCallbackUrl,
                payload,
                sender.id().value()
        );
    }

    private SenderStatus mapBspStatusToInternal(SenderStatus current, SenderSyncResult result) {
        // Twilio Channel Senders v2 lifecycle statuses we map to internal:
        //   ONLINE   → ACTIVE
        //   OFFLINE  → SUSPENDED   (Twilio took it down for compliance)
        //   FAILED   → FAILED
        //   PENDING* → PENDING_REVIEW (still in flight)
        if (current == SenderStatus.REVOKED) return current;       // terminal
        String raw = result.bspStatus() == null ? "" : result.bspStatus().toUpperCase();
        if (result.lastFailureCode() != null
                && result.lastFailureCode().startsWith("transient_")) {
            return current;                                        // transient → no transition
        }
        return switch (raw) {
            case "ONLINE", "ACTIVE", "VERIFIED"          -> SenderStatus.ACTIVE;
            case "OFFLINE", "SUSPENDED"                   -> SenderStatus.SUSPENDED;
            case "FAILED", "REJECTED"                     -> SenderStatus.FAILED;
            default                                       -> current;
        };
    }

    private int bumpAttempts(WhatsAppSenderEntity entity) {
        try {
            OnboardingState prev = json.readValue(entity.getOnboardingStateJson(), OnboardingState.class);
            return prev.pollAttempts() + 1;
        } catch (Exception ignored) {
            return 1;
        }
    }

    private WhatsAppSender transition(Ids.TenantId tenant, SenderId id,
                                      SenderStatus to, String reason) {
        WhatsAppSenderEntity entity = loadEntity(tenant, id);
        SenderStatus from = SenderStatus.valueOf(entity.getStatus());
        requireTransition(entity, to);
        entity.setStatus(to.name());
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        WhatsAppSender saved = toDomain(repo.save(entity));
        publishStatusChange(saved, from, to, reason);
        return saved;
    }

    private void publishStatusChange(WhatsAppSender saved, SenderStatus from,
                                     SenderStatus to, String reason) {
        events.publishEvent(new WhatsAppSenderStatusChangedEvent(
                saved.tenant(), saved.id(), from, to, reason));
    }

    private void requireTransition(WhatsAppSenderEntity entity, SenderStatus to) {
        SenderStatus from = SenderStatus.valueOf(entity.getStatus());
        if (!from.canTransitionTo(to)) {
            throw AdminApiException.conflict("illegal_transition",
                    "sender in status " + from + " cannot transition to " + to);
        }
    }

    private WhatsAppSenderEntity loadEntity(Ids.TenantId tenant, SenderId id) {
        return repo.findByTenantIdAndId(tenant.value(), id.value())
                .orElseThrow(() -> AdminApiException.notFound(
                        "sender_not_found",
                        "no such WhatsApp sender '" + id.value() + "' in tenant '" + tenant.value() + "'"));
    }

    private SenderId mintSenderId() {
        return new SenderId("snd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
    }

    private String mintWebhookSecret() {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    private static void validatePhone(String phoneE164) {
        if (phoneE164 == null || !E164.matcher(phoneE164).matches()) {
            throw AdminApiException.badRequest("bad_phone",
                    "phoneE164 must be E.164 (+ followed by 7–18 digits, first digit 1–9)");
        }
    }

    private static void validateDisplayName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw AdminApiException.badRequest("bad_display_name", "displayName must not be blank");
        }
        if (displayName.length() > 128) {
            throw AdminApiException.badRequest("bad_display_name", "displayName must be ≤ 128 chars");
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not encode " + value.getClass().getSimpleName(), e);
        }
    }

    private <T> T readJson(String src, Class<T> type) {
        try {
            return json.readValue(src, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not decode " + type.getSimpleName(), e);
        }
    }

    private Map<String, Object> readMetadata(String src) {
        if (src == null || src.isBlank()) return Map.of();
        try {
            return json.readValue(src, METADATA_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not decode metadata_json", e);
        }
    }

    WhatsAppSender toDomain(WhatsAppSenderEntity entity) {
        BusinessProfile business    = readJson(entity.getBusinessProfileJson(), BusinessProfile.class);
        OnboardingState onboarding  = readJson(entity.getOnboardingStateJson(), OnboardingState.class);
        Map<String, Object> meta    = readMetadata(entity.getMetadataJson());
        String webhookSecret        = cipher.decrypt(entity.getWebhookSecretEnc());

        return new WhatsAppSender(
                new SenderId(entity.getId()),
                new Ids.TenantId(entity.getTenantId()),
                entity.getPhoneE164(),
                entity.getDisplayName(),
                entity.getApprovedDisplayName(),
                business,
                SenderStatus.valueOf(entity.getStatus()),
                onboarding,
                webhookSecret,
                entity.getDefaultLocale(),
                meta,
                toInstant(entity.getCreatedAt()),
                toInstant(entity.getUpdatedAt()),
                toInstant(entity.getLastSyncAt())
        );
    }

    private static Instant toInstant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }
}
