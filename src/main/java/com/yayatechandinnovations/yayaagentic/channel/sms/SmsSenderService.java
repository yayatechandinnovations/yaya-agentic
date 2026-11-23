package com.yayatechandinnovations.yayaagentic.channel.sms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.operator_auth.config.SecretCipher;
import com.yayatechandinnovations.yayaagentic.persistence.SmsCampaignEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SmsCampaignRepository;
import com.yayatechandinnovations.yayaagentic.persistence.SmsSenderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SmsSenderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * SMS sender CRUD + state-machine arbiter, sibling to
 * {@code WhatsAppSenderService}. The defining difference is the
 * {@code PENDING_REVIEW -> ACTIVE} gate ({@code sms-channel-design.md} §5.2): a
 * US sender goes ACTIVE only once its A2P campaign is {@code APPROVED}; a DR
 * sender once its regulatory bundle is attached. The number-provisioning BSP
 * call lives behind {@link SmsNumberProvisioner}.
 */
@Service
public class SmsSenderService {

    private static final Logger log = LoggerFactory.getLogger(SmsSenderService.class);
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{6,17}$");
    private static final TypeReference<Map<String, Object>> METADATA = new TypeReference<>() {};

    private final SmsSenderRepository repo;
    private final SmsCampaignRepository campaigns;
    private final SmsNumberProvisioner provisioner;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public SmsSenderService(SmsSenderRepository repo,
                            SmsCampaignRepository campaigns,
                            SmsNumberProvisioner provisioner,
                            SecretCipher cipher,
                            ObjectMapper json) {
        this.repo = repo;
        this.campaigns = campaigns;
        this.provisioner = provisioner;
        this.cipher = cipher;
        this.json = json;
    }

    /* ------------------------------- read -------------------------------- */

    public List<SmsSender> listForTenant(TenantId tenant) {
        return repo.findByTenantIdOrderByCreatedAtDesc(tenant.value()).stream().map(this::toDomain).toList();
    }

    public SmsSender requireForTenant(TenantId tenant, SmsSenderId id) {
        return toDomain(loadEntity(tenant, id));
    }

    public Optional<TenantId> findTenantForSenderId(SmsSenderId id) {
        return repo.findById(id.value()).map(e -> new TenantId(e.getTenantId()));
    }

    /* ------------------------------ create ------------------------------- */

    @Transactional
    public SmsSender createDraft(TenantId tenant, CreateSenderCommand cmd) {
        validatePhone(cmd.phoneE164());
        if (cmd.market() == null) throw AdminApiException.badRequest("bad_market", "market required");

        // v1 path limits — the enums admit more, the service does not yet (§4.2).
        if (cmd.acquisition() == Acquisition.HOSTED) {
            throw AdminApiException.conflict("porting_unsupported",
                    "bringing an owned number (porting/hosting) is not available yet — buy a number for now");
        }
        if (cmd.senderKind() != null && cmd.senderKind() != SenderKind.LONG_CODE) {
            throw AdminApiException.conflict("sender_kind_unsupported",
                    "only long-code senders are supported in v1");
        }

        SmsCampaignId campaignId = resolveCampaignForCreate(tenant, cmd);

        repo.findLiveByPhoneE164(cmd.phoneE164()).ifPresent(existing -> {
            throw AdminApiException.conflict("phone_in_use",
                    "phone number " + cmd.phoneE164() + " is already registered to another sender");
        });

        String id = "sms_" + compactUuid();
        byte[] secretEnc = cipher.encrypt(mintWebhookSecret());
        if (secretEnc == null) throw new IllegalStateException("SecretCipher returned null for a non-empty plaintext");

        SmsSenderEntity e = new SmsSenderEntity(
                id, tenant.value(), cmd.phoneE164(), cmd.market().name(),
                SmsSenderStatus.DRAFT.name(), secretEnc);
        e.setSenderKind((cmd.senderKind() == null ? SenderKind.LONG_CODE : cmd.senderKind()).name());
        e.setAcquisition(Acquisition.PURCHASED.name());
        if (campaignId != null) e.setCampaignId(campaignId.value());
        e.setDefaultLocale(cmd.defaultLocale().filter(s -> !s.isBlank())
                .orElse(cmd.market() == SmsMarket.DO ? "es-DO" : "en-US"));
        e.setOnboardingStateJson(writeJson(SmsOnboardingState.empty()));
        e.setUpdatedAt(now());

        try {
            return toDomain(repo.save(e));
        } catch (DataIntegrityViolationException dup) {
            throw AdminApiException.conflict("phone_in_use",
                    "phone number " + cmd.phoneE164() + " is already registered to another sender");
        }
    }

    /* ------------------------------ submit ------------------------------- */

    public SmsSender submit(TenantId tenant, SmsSenderId id) {
        if (!provisioner.isReady()) {
            throw AdminApiException.conflict("provider_not_ready",
                    "SMS provisioning is not configured on this instance.");
        }
        // DR senders need their regulatory bundle attached before submit (§7.2);
        // US senders' campaign existence was enforced at create.
        SmsSender current = requireForTenant(tenant, id);
        if (current.market() == SmsMarket.DO && current.regulatoryBundleSid() == null) {
            throw AdminApiException.conflict("bundle_required",
                    "a Dominican Republic number requires an approved regulatory bundle before submission");
        }

        SmsSender pending = beginSubmit(tenant, id);
        SenderProvisionResult result;
        try {
            result = provisioner.provision(pending);
        } catch (RuntimeException ex) {
            log.warn("SmsNumberProvisioner.provision threw for sender {}: {}", id.value(), ex.getMessage());
            result = new SenderProvisionResult.TransientFailure("transport_error", ex.getMessage());
        }
        return finishSubmit(tenant, id, result);
    }

    @Transactional
    protected SmsSender beginSubmit(TenantId tenant, SmsSenderId id) {
        SmsSenderEntity e = loadEntity(tenant, id);
        requireTransition(e, SmsSenderStatus.SUBMITTING);
        e.setStatus(SmsSenderStatus.SUBMITTING.name());
        e.setUpdatedAt(now());
        return toDomain(repo.save(e));
    }

    @Transactional
    protected SmsSender finishSubmit(TenantId tenant, SmsSenderId id, SenderProvisionResult result) {
        SmsSenderEntity e = loadEntity(tenant, id);
        switch (result) {
            case SenderProvisionResult.Accepted acc -> {
                e.setStatus(SmsSenderStatus.PENDING_REVIEW.name());
                e.setTwilioSenderSid(acc.twilioSenderSid());
                e.setOnboardingStateJson(writeJson(new SmsOnboardingState(
                        acc.twilioSenderSid(), acc.bspStatus(), null, null, Instant.now(), 0)));
            }
            case SenderProvisionResult.Rejected rej -> {
                e.setStatus(SmsSenderStatus.FAILED.name());
                e.setOnboardingStateJson(writeJson(new SmsOnboardingState(
                        e.getTwilioSenderSid(), "REJECTED", rej.code(), rej.message(), Instant.now(), 1)));
            }
            case SenderProvisionResult.TransientFailure t ->
                    e.setOnboardingStateJson(writeJson(new SmsOnboardingState(
                            e.getTwilioSenderSid(), "RETRY_PENDING", t.code(), t.message(), Instant.now(), 1)));
        }
        e.setUpdatedAt(now());
        e.setLastSyncAt(now());
        return toDomain(repo.save(e));
    }

    /* ------------------------------- sync -------------------------------- */

    public SmsSender forceSync(TenantId tenant, SmsSenderId id) {
        SmsSender sender = requireForTenant(tenant, id);
        if (sender.onboarding().twilioSenderSid() == null) {
            throw AdminApiException.conflict("not_submitted",
                    "sender has not been submitted yet — there is nothing to sync");
        }
        return syncOne(sender);
    }

    /** Driven by forceSync and the (future) sender poller. */
    public SmsSender syncOne(SmsSender sender) {
        SmsNumberProvisioner.ProvisionPoll poll = provisioner.poll(sender);
        return applyPoll(sender.tenant(), sender.id(), poll);
    }

    @Transactional
    protected SmsSender applyPoll(TenantId tenant, SmsSenderId id, SmsNumberProvisioner.ProvisionPoll poll) {
        SmsSenderEntity e = loadEntity(tenant, id);
        SmsSenderStatus from = SmsSenderStatus.valueOf(e.getStatus());
        SmsSenderStatus next = from;

        if (poll.failureCode() != null) {
            next = SmsSenderStatus.FAILED;
        } else if (from == SmsSenderStatus.PENDING_REVIEW && poll.providerReady() && complianceGateSatisfied(e)) {
            // Both the BSP and the compliance gate (campaign APPROVED / bundle) agree.
            next = SmsSenderStatus.ACTIVE;
        }

        if (next != from && from.canTransitionTo(next)) {
            e.setStatus(next.name());
        }
        e.setOnboardingStateJson(writeJson(new SmsOnboardingState(
                e.getTwilioSenderSid(),
                poll.providerReady() ? "ONLINE" : "PENDING",
                poll.failureCode(), poll.failureMessage(), Instant.now(), bumpAttempts(e))));
        e.setUpdatedAt(now());
        e.setLastSyncAt(now());
        return toDomain(repo.save(e));
    }

    /** The §5.2 gate: a US sender needs an APPROVED campaign; a DR sender a bundle. */
    private boolean complianceGateSatisfied(SmsSenderEntity e) {
        SmsMarket market = SmsMarket.valueOf(e.getMarket());
        if (market == SmsMarket.US) {
            if (e.getCampaignId() == null) return false;
            return campaigns.findById(e.getCampaignId())
                    .map(c -> CampaignStatus.valueOf(c.getStatus()) == CampaignStatus.APPROVED)
                    .orElse(false);
        }
        return e.getRegulatoryBundleSid() != null;   // DR: bundle attached == approved (dev)
    }

    public List<SmsSender> findInFlight() {
        return repo.findByStatusIn(List.of(
                        SmsSenderStatus.SUBMITTING.name(), SmsSenderStatus.PENDING_REVIEW.name()))
                .stream().filter(e -> e.getTwilioSenderSid() != null).map(this::toDomain).toList();
    }

    /* --------------------------- other transitions ----------------------- */

    @Transactional
    public SmsSender attachRegulatoryBundle(TenantId tenant, SmsSenderId id, String bundleSid) {
        SmsSenderEntity e = loadEntity(tenant, id);
        if (SmsMarket.valueOf(e.getMarket()) != SmsMarket.DO) {
            throw AdminApiException.conflict("not_dr_sender", "only DR senders carry a regulatory bundle");
        }
        if (e.getCampaignId() != null) {
            throw AdminApiException.conflict("compliance_path_conflict", "a bundle-backed sender cannot also carry a campaign");
        }
        e.setRegulatoryBundleSid(bundleSid);
        e.setUpdatedAt(now());
        return toDomain(repo.save(e));
    }

    @Transactional
    public SmsSender suspend(TenantId tenant, SmsSenderId id) {
        return transition(tenant, id, SmsSenderStatus.SUSPENDED);
    }

    @Transactional
    public SmsSender resume(TenantId tenant, SmsSenderId id) {
        return transition(tenant, id, SmsSenderStatus.ACTIVE);
    }

    public SmsSender revoke(TenantId tenant, SmsSenderId id) {
        SmsSender sender = requireForTenant(tenant, id);
        if (sender.onboarding().twilioSenderSid() != null && provisioner.isReady()) {
            try {
                provisioner.revoke(sender);
            } catch (RuntimeException ex) {
                log.warn("SMS provisioner revoke failed for {} (still flipping REVOKED locally): {}",
                        id.value(), ex.getMessage());
            }
        }
        return transition(tenant, id, SmsSenderStatus.REVOKED);
    }

    /* ------------------------------ helpers ------------------------------ */

    private SmsCampaignId resolveCampaignForCreate(TenantId tenant, CreateSenderCommand cmd) {
        if (cmd.market() == SmsMarket.US) {
            if (cmd.campaignId() == null) {
                throw AdminApiException.badRequest("campaign_required",
                        "a US sender must be attached to an A2P campaign");
            }
            SmsCampaignEntity c = campaigns.findByTenantIdAndId(tenant.value(), cmd.campaignId().value())
                    .orElseThrow(() -> AdminApiException.badRequest("campaign_not_found",
                            "no such campaign '" + cmd.campaignId().value() + "' for this tenant"));
            return new SmsCampaignId(c.getId());
        }
        if (cmd.campaignId() != null) {
            throw AdminApiException.badRequest("campaign_not_allowed",
                    "a non-US sender does not attach to an A2P campaign");
        }
        return null;
    }

    private SmsSender transition(TenantId tenant, SmsSenderId id, SmsSenderStatus to) {
        SmsSenderEntity e = loadEntity(tenant, id);
        requireTransition(e, to);
        e.setStatus(to.name());
        e.setUpdatedAt(now());
        return toDomain(repo.save(e));
    }

    private void requireTransition(SmsSenderEntity e, SmsSenderStatus to) {
        SmsSenderStatus from = SmsSenderStatus.valueOf(e.getStatus());
        if (!from.canTransitionTo(to)) {
            throw AdminApiException.conflict("illegal_transition",
                    "sender in status " + from + " cannot transition to " + to);
        }
    }

    private int bumpAttempts(SmsSenderEntity e) {
        try {
            return json.readValue(e.getOnboardingStateJson(), SmsOnboardingState.class).pollAttempts() + 1;
        } catch (Exception ignored) {
            return 1;
        }
    }

    private SmsSenderEntity loadEntity(TenantId tenant, SmsSenderId id) {
        return repo.findByTenantIdAndId(tenant.value(), id.value())
                .orElseThrow(() -> AdminApiException.notFound("sender_not_found",
                        "no such SMS sender '" + id.value() + "' in tenant '" + tenant.value() + "'"));
    }

    private static void validatePhone(String phoneE164) {
        if (phoneE164 == null || !E164.matcher(phoneE164).matches()) {
            throw AdminApiException.badRequest("bad_phone",
                    "phoneE164 must be E.164 (+ followed by 7–18 digits, first digit 1–9)");
        }
    }

    private String mintWebhookSecret() {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    SmsSender toDomain(SmsSenderEntity e) {
        SmsOnboardingState onboarding = readJson(e.getOnboardingStateJson(), SmsOnboardingState.class);
        return new SmsSender(
                new SmsSenderId(e.getId()),
                new TenantId(e.getTenantId()),
                e.getPhoneE164(),
                SmsMarket.valueOf(e.getMarket()),
                SenderKind.valueOf(e.getSenderKind()),
                Acquisition.valueOf(e.getAcquisition()),
                e.getCampaignId() == null ? null : new SmsCampaignId(e.getCampaignId()),
                e.getRegulatoryBundleSid(),
                SmsSenderStatus.valueOf(e.getStatus()),
                onboarding,
                cipher.decrypt(e.getWebhookSecretEnc()),
                e.getDefaultLocale(),
                readMetadata(e.getMetadataJson()),
                toInstant(e.getCreatedAt()),
                toInstant(e.getUpdatedAt()),
                toInstant(e.getLastSyncAt()));
    }

    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private static Instant toInstant(OffsetDateTime odt) { return odt == null ? null : odt.toInstant(); }
    private static String compactUuid() { return UUID.randomUUID().toString().replace("-", "").substring(0, 24); }

    private String writeJson(Object v) {
        try { return json.writeValueAsString(v); }
        catch (JsonProcessingException e) { throw new IllegalStateException("encode " + v.getClass().getSimpleName(), e); }
    }

    private <T> T readJson(String src, Class<T> type) {
        try { return json.readValue(src, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("decode " + type.getSimpleName(), e); }
    }

    private Map<String, Object> readMetadata(String src) {
        if (src == null || src.isBlank()) return Map.of();
        try { return json.readValue(src, METADATA); }
        catch (JsonProcessingException e) { throw new IllegalStateException("decode metadata_json", e); }
    }

    /** Create-sender command. Keeps the service signature stable as senders grow. */
    public record CreateSenderCommand(
            String           phoneE164,
            SmsMarket        market,
            SenderKind       senderKind,
            Acquisition      acquisition,
            SmsCampaignId    campaignId,
            Optional<String> defaultLocale) {

        public CreateSenderCommand {
            defaultLocale = defaultLocale == null ? Optional.empty() : defaultLocale;
        }
    }
}
