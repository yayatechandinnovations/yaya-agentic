package com.yayatechandinnovations.yayaagentic.channel.sms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.persistence.SmsBrandEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SmsBrandRepository;
import com.yayatechandinnovations.yayaagentic.persistence.SmsCampaignEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SmsCampaignRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A2P 10DLC compliance arbiter — Brand + Campaign CRUD and the two state
 * machines ({@link BrandStatus}, {@link CampaignStatus}). The controller is a
 * thin REST surface over this; the poller (S1d) drives carrier-side transitions
 * through {@link #applyBrandPoll} / {@link #applyCampaignPoll}, never through
 * the repositories directly.
 *
 * <p>Carrier/Twilio specifics live entirely behind {@link A2pRegistrar} — this
 * service never imports anything Twilio. That keeps the design's §2 invariant
 * (Twilio + TCR invisible to the tenant) true at the code layer too.
 */
@Service
public class SmsComplianceService {

    private static final Logger log = LoggerFactory.getLogger(SmsComplianceService.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> STRING_MAP = new TypeReference<>() {};

    private final SmsBrandRepository brands;
    private final SmsCampaignRepository campaigns;
    private final A2pRegistrar registrar;
    private final ObjectMapper json;

    public SmsComplianceService(SmsBrandRepository brands,
                                SmsCampaignRepository campaigns,
                                A2pRegistrar registrar,
                                ObjectMapper json) {
        this.brands = brands;
        this.campaigns = campaigns;
        this.registrar = registrar;
        this.json = json;
    }

    /* ============================ BRAND ================================== */

    public Optional<SmsBrand> findBrand(TenantId tenant) {
        return brands.findByTenantIdOrderByCreatedAtDesc(tenant.value()).stream()
                .findFirst()
                .map(this::toBrand);
    }

    @Transactional
    public SmsBrand createBrand(TenantId tenant, BusinessIdentity business) {
        // v1: one Brand per tenant (§4.2 — the table admits N; the rule is here).
        if (!brands.findByTenantId(tenant.value()).isEmpty()) {
            throw AdminApiException.conflict("brand_exists",
                    "tenant '" + tenant.value() + "' already has an SMS brand; edit it instead");
        }
        SmsBrandId id = new SmsBrandId("brd_" + compactUuid());
        SmsBrandEntity entity = new SmsBrandEntity(
                id.value(), tenant.value(), writeJson(business), BrandStatus.DRAFT.name());
        return toBrand(brands.save(entity));
    }

    @Transactional
    public SmsBrand editBrand(TenantId tenant, SmsBrandId id, BusinessIdentity business) {
        SmsBrandEntity entity = loadBrand(tenant, id);
        BrandStatus status = BrandStatus.valueOf(entity.getStatus());
        if (!status.isEditable()) {
            throw AdminApiException.conflict("not_editable",
                    "brand in status " + status + " cannot be edited — only DRAFT or FAILED are editable");
        }
        entity.setBusinessJson(writeJson(business));
        if (status == BrandStatus.FAILED) {
            entity.setStatus(BrandStatus.DRAFT.name());   // FAILED → DRAFT on edit (§6.2)
            entity.setLastFailureCode(null);
            entity.setLastFailureMessage(null);
        }
        entity.setUpdatedAt(now());
        return toBrand(brands.save(entity));
    }

    public SmsBrand submitBrand(TenantId tenant, SmsBrandId id) {
        requireRegistrarReady();
        SmsBrand pending = beginBrandSubmit(tenant, id);

        A2pSubmitResult result;
        try {
            result = registrar.submitBrand(pending);
        } catch (RuntimeException e) {
            log.warn("A2pRegistrar.submitBrand threw for brand {}: {}", id.value(), e.getMessage());
            result = new A2pSubmitResult.TransientFailure("transport_error", e.getMessage());
        }
        return finishBrandSubmit(tenant, id, result);
    }

    @Transactional
    protected SmsBrand beginBrandSubmit(TenantId tenant, SmsBrandId id) {
        SmsBrandEntity entity = loadBrand(tenant, id);
        requireBrandTransition(entity, BrandStatus.SUBMITTING);
        entity.setStatus(BrandStatus.SUBMITTING.name());
        entity.setUpdatedAt(now());
        return toBrand(brands.save(entity));
    }

    @Transactional
    protected SmsBrand finishBrandSubmit(TenantId tenant, SmsBrandId id, A2pSubmitResult result) {
        SmsBrandEntity entity = loadBrand(tenant, id);
        switch (result) {
            case A2pSubmitResult.Accepted acc -> {
                entity.setStatus(BrandStatus.PENDING_VERIFICATION.name());
                entity.setTwilioProfileSid(acc.externalId());
                entity.setLastFailureCode(null);
                entity.setLastFailureMessage(null);
            }
            case A2pSubmitResult.Rejected rej -> {
                entity.setStatus(BrandStatus.FAILED.name());
                entity.setLastFailureCode(rej.code());
                entity.setLastFailureMessage(rej.message());
            }
            case A2pSubmitResult.TransientFailure t -> {
                // stay in SUBMITTING; the poller retries
                entity.setLastFailureCode(t.code());
                entity.setLastFailureMessage(t.message());
            }
        }
        entity.setUpdatedAt(now());
        entity.setLastSyncAt(now());
        return toBrand(brands.save(entity));
    }

    /** Folds a poller verdict back into a Brand (S1d). */
    @Transactional
    public SmsBrand applyBrandPoll(SmsBrandId id, A2pRegistrar.BrandPoll poll) {
        SmsBrandEntity entity = brands.findById(id.value())
                .orElseThrow(() -> AdminApiException.notFound("brand_not_found", id.value()));
        entity.setStatus(poll.status().name());
        if (poll.tcrBrandId() != null) entity.setTcrBrandId(poll.tcrBrandId());
        entity.setLastFailureCode(poll.failureCode());
        entity.setLastFailureMessage(poll.failureMessage());
        entity.setUpdatedAt(now());
        entity.setLastSyncAt(now());
        return toBrand(brands.save(entity));
    }

    /* ============================ CAMPAIGN =============================== */

    public List<SmsCampaign> listCampaigns(TenantId tenant) {
        return campaigns.findByTenantIdOrderByCreatedAtDesc(tenant.value()).stream()
                .map(this::toCampaign)
                .toList();
    }

    public SmsCampaign requireCampaign(TenantId tenant, SmsCampaignId id) {
        return toCampaign(loadCampaign(tenant, id));
    }

    @Transactional
    public SmsCampaign createCampaign(TenantId tenant, CreateCampaignCommand cmd) {
        SmsBrandEntity brand = brands.findByTenantIdAndId(tenant.value(), cmd.brandId().value())
                .orElseThrow(() -> AdminApiException.badRequest("brand_not_found",
                        "no such brand '" + cmd.brandId().value() + "' for this tenant — create the brand first"));

        // restricted use cases (gambling, sweepstakes, …) force operator review
        // before they may be pushed to carriers (§6.3).
        boolean requiresReview = cmd.useCase().isRestricted();

        SmsCampaignId id = new SmsCampaignId("cmp_" + compactUuid());
        SmsCampaignEntity entity = new SmsCampaignEntity(
                id.value(), tenant.value(), brand.getId(),
                cmd.useCase().name(), cmd.description(),
                writeJson(cmd.sampleMessages()), writeJson(cmd.optIn()),
                requiresReview, CampaignStatus.DRAFT.name());
        if (cmd.evidence() != null && !cmd.evidence().isEmpty()) {
            entity.setEvidenceJson(writeJson(cmd.evidence()));
        }
        // validate 2..5 sample messages etc. via the domain record's invariants
        validateCampaignContent(cmd.useCase(), cmd.sampleMessages(), cmd.optIn());
        return toCampaign(campaigns.save(entity));
    }

    public SmsCampaign submitCampaign(TenantId tenant, SmsCampaignId id) {
        requireRegistrarReady();
        SmsCampaign pending = beginCampaignSubmit(tenant, id);
        SmsBrand brand = toBrand(brands.findById(pending.brand().value())
                .orElseThrow(() -> AdminApiException.notFound("brand_not_found", pending.brand().value())));

        A2pSubmitResult result;
        try {
            result = registrar.submitCampaign(pending, brand);
        } catch (RuntimeException e) {
            log.warn("A2pRegistrar.submitCampaign threw for campaign {}: {}", id.value(), e.getMessage());
            result = new A2pSubmitResult.TransientFailure("transport_error", e.getMessage());
        }
        return finishCampaignSubmit(tenant, id, result);
    }

    @Transactional
    protected SmsCampaign beginCampaignSubmit(TenantId tenant, SmsCampaignId id) {
        SmsCampaignEntity entity = loadCampaign(tenant, id);
        A2pUseCase useCase = A2pUseCase.valueOf(entity.getUseCase());

        // Gate 1: the Brand must be VERIFIED before any campaign submission (§6.2).
        SmsBrandEntity brand = brands.findById(entity.getBrandId())
                .orElseThrow(() -> AdminApiException.notFound("brand_not_found", entity.getBrandId()));
        if (!BrandStatus.valueOf(brand.getStatus()).canHostCampaignSubmission()) {
            throw AdminApiException.conflict("brand_not_verified",
                    "brand must be VERIFIED before a campaign can be submitted (currently " + brand.getStatus() + ")");
        }
        // Gate 2: restricted use cases need platform-operator release first (§6.3).
        if (entity.isRequiresOperatorReview()) {
            throw AdminApiException.conflict("operator_review_required",
                    "this " + useCase + " campaign needs platform-operator review before it can be submitted to carriers");
        }
        // Gate 3: restricted use cases must carry licensing evidence.
        if (useCase.isRestricted() && (entity.getEvidenceJson() == null || entity.getEvidenceJson().isBlank())) {
            throw AdminApiException.conflict("evidence_required",
                    "restricted use case " + useCase + " requires licensing/jurisdiction evidence before submission");
        }

        requireCampaignTransition(entity, CampaignStatus.SUBMITTING);
        entity.setStatus(CampaignStatus.SUBMITTING.name());
        entity.setUpdatedAt(now());
        return toCampaign(campaigns.save(entity));
    }

    @Transactional
    protected SmsCampaign finishCampaignSubmit(TenantId tenant, SmsCampaignId id, A2pSubmitResult result) {
        SmsCampaignEntity entity = loadCampaign(tenant, id);
        switch (result) {
            case A2pSubmitResult.Accepted acc -> {
                entity.setStatus(CampaignStatus.PENDING_REVIEW.name());
                entity.setTwilioCampaignSid(acc.externalId());
                entity.setMessagingServiceSid(acc.messagingServiceSid());
                entity.setLastFailureCode(null);
                entity.setLastFailureMessage(null);
            }
            case A2pSubmitResult.Rejected rej -> {
                entity.setStatus(CampaignStatus.FAILED.name());
                entity.setLastFailureCode(rej.code());
                entity.setLastFailureMessage(rej.message());
            }
            case A2pSubmitResult.TransientFailure t -> {
                entity.setLastFailureCode(t.code());
                entity.setLastFailureMessage(t.message());
            }
        }
        entity.setUpdatedAt(now());
        entity.setLastSyncAt(now());
        return toCampaign(campaigns.save(entity));
    }

    /** Folds a poller verdict back into a Campaign (S1d). */
    @Transactional
    public SmsCampaign applyCampaignPoll(SmsCampaignId id, A2pRegistrar.CampaignPoll poll) {
        SmsCampaignEntity entity = campaigns.findById(id.value())
                .orElseThrow(() -> AdminApiException.notFound("campaign_not_found", id.value()));
        entity.setStatus(poll.status().name());
        entity.setLastFailureCode(poll.failureCode());
        entity.setLastFailureMessage(poll.failureMessage());
        entity.setUpdatedAt(now());
        entity.setLastSyncAt(now());
        return toCampaign(campaigns.save(entity));
    }

    /**
     * Platform-operator release of a restricted campaign for carrier submission
     * (§6.3). Cross-tenant by id — this is an operator-plane action surfaced in
     * the operator console, not a tenant-facing endpoint.
     */
    @Transactional
    public SmsCampaign releaseForSubmission(SmsCampaignId id) {
        SmsCampaignEntity entity = campaigns.findById(id.value())
                .orElseThrow(() -> AdminApiException.notFound("campaign_not_found", id.value()));
        entity.setRequiresOperatorReview(false);
        entity.setUpdatedAt(now());
        return toCampaign(campaigns.save(entity));
    }

    /* ---- in-flight sweeps for the poller (S1d) -------------------------- */

    /** Poll one Brand at the registry and fold the verdict back. Driven by the
     *  scheduled {@code A2pSyncJob}; transition logic stays here, not in the job. */
    public SmsBrand syncBrand(SmsBrand brand) {
        return applyBrandPoll(brand.id(), registrar.pollBrand(brand));
    }

    /** Poll one Campaign at the registry and fold the verdict back. */
    public SmsCampaign syncCampaign(SmsCampaign campaign) {
        return applyCampaignPoll(campaign.id(), registrar.pollCampaign(campaign));
    }

    public List<SmsBrand> brandsInFlight() {
        return brands.findByStatusIn(List.of(
                        BrandStatus.SUBMITTING.name(), BrandStatus.PENDING_VERIFICATION.name()))
                .stream().filter(e -> e.getTwilioProfileSid() != null).map(this::toBrand).toList();
    }

    public List<SmsCampaign> campaignsInFlight() {
        return campaigns.findByStatusIn(List.of(
                        CampaignStatus.SUBMITTING.name(), CampaignStatus.PENDING_REVIEW.name()))
                .stream().filter(e -> e.getTwilioCampaignSid() != null).map(this::toCampaign).toList();
    }

    /* ============================ helpers =============================== */

    private void requireRegistrarReady() {
        if (!registrar.isReady()) {
            throw AdminApiException.conflict("provider_not_ready",
                    "A2P 10DLC registration is not configured on this instance.");
        }
    }

    private void requireBrandTransition(SmsBrandEntity entity, BrandStatus to) {
        BrandStatus from = BrandStatus.valueOf(entity.getStatus());
        if (!from.canTransitionTo(to)) {
            throw AdminApiException.conflict("illegal_transition",
                    "brand in status " + from + " cannot transition to " + to);
        }
    }

    private void requireCampaignTransition(SmsCampaignEntity entity, CampaignStatus to) {
        CampaignStatus from = CampaignStatus.valueOf(entity.getStatus());
        if (!from.canTransitionTo(to)) {
            throw AdminApiException.conflict("illegal_transition",
                    "campaign in status " + from + " cannot transition to " + to);
        }
    }

    private void validateCampaignContent(A2pUseCase useCase, List<String> samples, OptInDescription optIn) {
        if (samples == null || samples.size() < 2 || samples.size() > 5) {
            throw AdminApiException.badRequest("bad_samples", "provide 2..5 sample messages");
        }
        if (optIn == null || optIn.description() == null || optIn.description().isBlank()) {
            throw AdminApiException.badRequest("bad_opt_in", "opt-in description is required");
        }
    }

    private SmsBrandEntity loadBrand(TenantId tenant, SmsBrandId id) {
        return brands.findByTenantIdAndId(tenant.value(), id.value())
                .orElseThrow(() -> AdminApiException.notFound("brand_not_found",
                        "no such brand '" + id.value() + "' in tenant '" + tenant.value() + "'"));
    }

    private SmsCampaignEntity loadCampaign(TenantId tenant, SmsCampaignId id) {
        return campaigns.findByTenantIdAndId(tenant.value(), id.value())
                .orElseThrow(() -> AdminApiException.notFound("campaign_not_found",
                        "no such campaign '" + id.value() + "' in tenant '" + tenant.value() + "'"));
    }

    SmsBrand toBrand(SmsBrandEntity e) {
        return new SmsBrand(
                new SmsBrandId(e.getId()),
                new TenantId(e.getTenantId()),
                readJson(e.getBusinessJson(), BusinessIdentity.class),
                BrandStatus.valueOf(e.getStatus()),
                e.getTwilioProfileSid(),
                e.getTcrBrandId(),
                e.getLastFailureCode(),
                e.getLastFailureMessage(),
                toInstant(e.getCreatedAt()),
                toInstant(e.getUpdatedAt()),
                toInstant(e.getLastSyncAt()));
    }

    SmsCampaign toCampaign(SmsCampaignEntity e) {
        Map<String, Object> evidence = (e.getEvidenceJson() == null || e.getEvidenceJson().isBlank())
                ? null : readMap(e.getEvidenceJson());
        return new SmsCampaign(
                new SmsCampaignId(e.getId()),
                new TenantId(e.getTenantId()),
                new SmsBrandId(e.getBrandId()),
                A2pUseCase.valueOf(e.getUseCase()),
                e.getDescription(),
                readList(e.getSampleMessagesJson()),
                readJson(e.getOptInJson(), OptInDescription.class),
                evidence,
                e.isRequiresOperatorReview(),
                CampaignStatus.valueOf(e.getStatus()),
                e.getTwilioCampaignSid(),
                e.getMessagingServiceSid(),
                e.getLastFailureCode(),
                e.getLastFailureMessage(),
                toInstant(e.getCreatedAt()),
                toInstant(e.getUpdatedAt()),
                toInstant(e.getLastSyncAt()));
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static Instant toInstant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }

    private static String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
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

    private List<String> readList(String src) {
        try {
            return json.readValue(src, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not decode sample_messages_json", e);
        }
    }

    private Map<String, Object> readMap(String src) {
        try {
            return json.readValue(src, STRING_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not decode evidence_json", e);
        }
    }

    /** Create-campaign command — keeps the service signature stable as the
     *  campaign shape grows. */
    public record CreateCampaignCommand(
            SmsBrandId          brandId,
            A2pUseCase          useCase,
            String              description,
            List<String>        sampleMessages,
            OptInDescription    optIn,
            Map<String, Object> evidence) {}
}
