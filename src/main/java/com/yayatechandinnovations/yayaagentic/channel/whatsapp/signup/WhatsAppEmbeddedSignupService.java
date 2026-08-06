package com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup;

import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.BusinessProfile;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.ChannelMetrics;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderStatus;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppOnboardingAttemptEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppOnboardingAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Orchestrates one embedded-signup onboarding end-to-end: validate →
 * ownership checks → get-or-provision the WABA's dedicated subaccount →
 * create-or-reuse the sender draft → submit to the Senders API. Every phase
 * transition is durably recorded on the attempt row, and the whole flow is
 * idempotent on (tenant, phone, WABA): refreshes, double-clicks, and
 * timeouts resume the same attempt rather than provisioning duplicates.
 *
 * <p>No transaction spans a Twilio call — attempt-state updates are small
 * transactional writes around the non-transactional provider calls, the
 * same split as {@code WhatsAppSenderService.submit}.
 */
@Service
public class WhatsAppEmbeddedSignupService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppEmbeddedSignupService.class);
    private static final Pattern WABA_ID = Pattern.compile("^[0-9]{5,32}$");
    private static final Pattern META_PHONE_NUMBER_ID = Pattern.compile("^[0-9]{5,32}$");

    private final WhatsAppOnboardingAttemptRepository attempts;
    private final WhatsAppMessagingAccountService accounts;
    private final WhatsAppSenderService senders;
    private final EmbeddedSignupProperties props;
    private final ChannelMetrics metrics;

    public WhatsAppEmbeddedSignupService(WhatsAppOnboardingAttemptRepository attempts,
                                         WhatsAppMessagingAccountService accounts,
                                         WhatsAppSenderService senders,
                                         EmbeddedSignupProperties props,
                                         ChannelMetrics metrics) {
        this.attempts = attempts;
        this.accounts = accounts;
        this.senders = senders;
        this.props = props;
        this.metrics = metrics;
    }

    /** Everything the backend needs from the wizard — step-1 form data plus
     *  the ids captured from Meta's Embedded Signup FINISH event. */
    public record OnboardingRequest(
            String phoneE164,
            String wabaId,
            String metaPhoneNumberId,
            String displayName,
            BusinessProfile business,
            Optional<String> defaultLocale
    ) {}

    public record OnboardingResult(OnboardingAttempt attempt, WhatsAppSender sender) {}

    /* ---------------------------- read ------------------------------------ */

    public List<OnboardingAttempt> listForTenant(Ids.TenantId tenant) {
        return attempts.findByTenantIdOrderByUpdatedAtDesc(tenant.value()).stream()
                .map(WhatsAppEmbeddedSignupService::toDomain)
                .toList();
    }

    public OnboardingAttempt requireForTenant(Ids.TenantId tenant, String attemptId) {
        return attempts.findByTenantIdAndId(tenant.value(), attemptId)
                .map(WhatsAppEmbeddedSignupService::toDomain)
                .orElseThrow(() -> AdminApiException.notFound("onboarding_attempt_not_found",
                        "no such onboarding attempt '" + attemptId + "' in tenant '"
                                + tenant.value() + "'"));
    }

    /* ---------------------------- orchestrate ----------------------------- */

    public OnboardingResult startOnboarding(Ids.TenantId tenant, OnboardingRequest req) {
        requireEnabled();
        validate(req);

        WhatsAppOnboardingAttemptEntity attempt = findOrCreateAttempt(tenant, req);
        if (OnboardingAttemptState.valueOf(attempt.getState()) == OnboardingAttemptState.COMPLETED) {
            // Replayed submission of a finished onboarding — pure idempotent read.
            return new OnboardingResult(toDomain(attempt), senderOf(tenant, attempt));
        }

        log.info("WhatsApp onboarding started tenant={} attempt={} waba={}",
                tenant.value(), attempt.getId(), attempt.getWabaId());
        metrics.embeddedSignup(tenant.value(), "started");

        try {
            // Phase A — cross-tenant ownership checks. Re-run on every resume:
            // data may have changed since the attempt was created.
            checkPhoneOwnership(tenant, req.phoneE164());

            // Phase B — the WABA's dedicated subaccount (created or reused).
            WhatsAppMessagingAccount account =
                    accounts.getOrProvision(tenant, req.wabaId(), businessName(req));
            attempt = markAccountProvisioned(attempt.getId(), account.id());

            // Phase C — the sender draft (created or reused).
            WhatsAppSender sender = createOrReuseSender(tenant, req, account);
            attempt = markSenderLinked(attempt.getId(), sender.id().value());

            // Phase D — Senders API registration (asynchronous on Twilio's
            // side; the sender lands in PENDING_REVIEW and the existing
            // poller drives it to ACTIVE when Twilio reports ONLINE).
            WhatsAppSender submitted = submitIfNeeded(tenant, sender);
            attempt = foldSubmitOutcome(attempt.getId(), submitted);

            metrics.embeddedSignup(tenant.value(),
                    OnboardingAttemptState.valueOf(attempt.getState())
                            == OnboardingAttemptState.COMPLETED ? "completed" : "pending");
            return new OnboardingResult(toDomain(attempt), submitted);
        } catch (AdminApiException e) {
            markFailure(attempt.getId(), e.code(), e.getReason(), isTerminalFailure(e));
            metrics.embeddedSignup(tenant.value(),
                    e.getStatusCode().value() == 409 ? "conflict" : "failed");
            throw e;
        }
    }

    /* ---------------------------- phases ---------------------------------- */

    private void checkPhoneOwnership(Ids.TenantId tenant, String phoneE164) {
        senders.findTenantForPhone(phoneE164)
                .filter(owner -> !owner.equals(tenant))
                .ifPresent(owner -> {
                    throw AdminApiException.conflict("phone_in_use",
                            "phone number " + phoneE164
                                    + " is already connected to a different workspace");
                });
    }

    private WhatsAppSender createOrReuseSender(Ids.TenantId tenant, OnboardingRequest req,
                                               WhatsAppMessagingAccount account) {
        Optional<WhatsAppSender> existing = senders.findByPhoneInTenant(tenant, req.phoneE164());
        if (existing.isEmpty()) {
            return senders.createEmbeddedDraft(tenant, req.phoneE164(), req.displayName(),
                    req.business(), req.defaultLocale(),
                    req.wabaId(), req.metaPhoneNumberId(), account.id());
        }

        WhatsAppSender sender = existing.get();
        if (sender.wabaId() != null && !sender.wabaId().equals(req.wabaId())) {
            throw AdminApiException.conflict("sender_waba_mismatch",
                    "this phone number is already linked to a different WhatsApp Business Account");
        }
        if (sender.status() == SenderStatus.REVOKED) {
            throw AdminApiException.conflict("sender_revoked",
                    "this phone number belonged to a revoked sender — contact support to reconnect it");
        }
        if (sender.isEditable()) {
            // Retry path: refresh the draft with the latest form data before
            // re-linking and resubmitting.
            senders.editDraft(tenant, sender.id(), req.phoneE164(), req.displayName(),
                    req.business(), req.defaultLocale(), Optional.empty());
        }
        return senders.linkEmbeddedSignup(tenant, sender.id(),
                req.wabaId(), req.metaPhoneNumberId(), account.id());
    }

    private WhatsAppSender submitIfNeeded(Ids.TenantId tenant, WhatsAppSender sender) {
        // Already past registration (resume after a refresh) — nothing to do.
        if (sender.status() == SenderStatus.PENDING_REVIEW
                || sender.status() == SenderStatus.ACTIVE
                || sender.status() == SenderStatus.SUSPENDED) {
            return sender;
        }
        if (sender.status() == SenderStatus.SUBMITTING) {
            // A concurrent submit (or an earlier transient failure awaiting
            // the poller's retry) owns this sender right now.
            return sender;
        }
        return senders.submit(tenant, sender.id());
    }

    /* ---------------------------- attempt row ----------------------------- */

    private WhatsAppOnboardingAttemptEntity findOrCreateAttempt(Ids.TenantId tenant,
                                                                OnboardingRequest req) {
        String key = idempotencyKey(tenant, req.phoneE164(), req.wabaId());
        Optional<WhatsAppOnboardingAttemptEntity> existing =
                attempts.findByTenantIdAndIdempotencyKey(tenant.value(), key);
        if (existing.isPresent()) {
            WhatsAppOnboardingAttemptEntity attempt = existing.get();
            if (OnboardingAttemptState.valueOf(attempt.getState()) == OnboardingAttemptState.FAILED) {
                return resetToReceived(attempt.getId(), req.metaPhoneNumberId());
            }
            return attempt;
        }
        try {
            return createAttempt(tenant, req, key);
        } catch (DataIntegrityViolationException race) {
            // Concurrent double-click — the other request created the row.
            return attempts.findByTenantIdAndIdempotencyKey(tenant.value(), key).orElseThrow();
        }
    }

    @Transactional
    protected WhatsAppOnboardingAttemptEntity createAttempt(Ids.TenantId tenant,
                                                            OnboardingRequest req, String key) {
        WhatsAppOnboardingAttemptEntity entity = new WhatsAppOnboardingAttemptEntity(
                "woa_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24),
                tenant.value(),
                req.phoneE164(),
                req.wabaId(),
                blankToNull(req.metaPhoneNumberId()),
                OnboardingAttemptState.RECEIVED.name(),
                key);
        return attempts.saveAndFlush(entity);
    }

    @Transactional
    protected WhatsAppOnboardingAttemptEntity resetToReceived(String attemptId,
                                                              String metaPhoneNumberId) {
        WhatsAppOnboardingAttemptEntity attempt = attempts.findById(attemptId).orElseThrow();
        attempt.setState(OnboardingAttemptState.RECEIVED.name());
        attempt.setFailureCode(null);
        attempt.setFailureMessage(null);
        attempt.setCompletedAt(null);
        if (metaPhoneNumberId != null && !metaPhoneNumberId.isBlank()) {
            attempt.setMetaPhoneNumberId(metaPhoneNumberId);
        }
        attempt.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return attempts.save(attempt);
    }

    @Transactional
    protected WhatsAppOnboardingAttemptEntity markAccountProvisioned(String attemptId,
                                                                     String accountId) {
        WhatsAppOnboardingAttemptEntity attempt = attempts.findById(attemptId).orElseThrow();
        attempt.setMessagingAccountId(accountId);
        if (OnboardingAttemptState.valueOf(attempt.getState()) == OnboardingAttemptState.RECEIVED) {
            attempt.setState(OnboardingAttemptState.ACCOUNT_PROVISIONED.name());
        }
        attempt.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return attempts.save(attempt);
    }

    @Transactional
    protected WhatsAppOnboardingAttemptEntity markSenderLinked(String attemptId, String senderId) {
        WhatsAppOnboardingAttemptEntity attempt = attempts.findById(attemptId).orElseThrow();
        attempt.setSenderId(senderId);
        attempt.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return attempts.save(attempt);
    }

    @Transactional
    protected WhatsAppOnboardingAttemptEntity foldSubmitOutcome(String attemptId,
                                                                WhatsAppSender submitted) {
        WhatsAppOnboardingAttemptEntity attempt = attempts.findById(attemptId).orElseThrow();
        switch (submitted.status()) {
            case PENDING_REVIEW, ACTIVE, SUSPENDED -> {
                attempt.setState(OnboardingAttemptState.COMPLETED.name());
                attempt.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
                attempt.setFailureCode(null);
                attempt.setFailureMessage(null);
                log.info("WhatsApp onboarding completed tenant={} attempt={} sender={}",
                        attempt.getTenantId(), attempt.getId(), submitted.id().value());
            }
            case SUBMITTING -> {
                // Twilio wasn't reachable; the sender poller retries the
                // registration. The attempt stays resumable.
                attempt.setState(OnboardingAttemptState.SENDER_SUBMITTED.name());
                attempt.setFailureCode(submitted.onboarding().lastTwilioFailureCode());
                attempt.setFailureMessage(OnboardingErrorSanitizer.sanitize(
                        submitted.onboarding().lastTwilioFailureMessage()));
            }
            case FAILED -> {
                attempt.setState(OnboardingAttemptState.FAILED.name());
                attempt.setFailureCode("sender_rejected");
                attempt.setFailureMessage(OnboardingErrorSanitizer.sanitize(
                        submitted.onboarding().lastTwilioFailureMessage()));
                log.warn("WhatsApp onboarding failed tenant={} attempt={} sender={} code={}",
                        attempt.getTenantId(), attempt.getId(), submitted.id().value(),
                        submitted.onboarding().lastTwilioFailureCode());
            }
            default -> attempt.setState(OnboardingAttemptState.SENDER_SUBMITTED.name());
        }
        attempt.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return attempts.save(attempt);
    }

    @Transactional
    protected void markFailure(String attemptId, String code, String message, boolean terminal) {
        attempts.findById(attemptId).ifPresent(attempt -> {
            if (terminal) {
                attempt.setState(OnboardingAttemptState.FAILED.name());
            }
            attempt.setFailureCode(code);
            attempt.setFailureMessage(OnboardingErrorSanitizer.sanitize(message));
            attempt.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            attempts.save(attempt);
        });
    }

    /** Transient provider outages keep the attempt resumable; ownership and
     *  validation conflicts are terminal until the operator changes input. */
    private static boolean isTerminalFailure(AdminApiException e) {
        return e.getStatusCode().value() != 503;
    }

    /* ---------------------------- helpers --------------------------------- */

    private void requireEnabled() {
        if (!props.enabled()) {
            throw AdminApiException.conflict("embedded_signup_disabled",
                    "WhatsApp self-service onboarding is not enabled on this instance");
        }
    }

    private static void validate(OnboardingRequest req) {
        if (req == null) {
            throw AdminApiException.badRequest("missing_body", "request body required");
        }
        if (req.wabaId() == null || !WABA_ID.matcher(req.wabaId()).matches()) {
            throw AdminApiException.badRequest("bad_waba_id",
                    "wabaId must be the numeric WhatsApp Business Account id returned by Meta");
        }
        if (req.metaPhoneNumberId() != null && !req.metaPhoneNumberId().isBlank()
                && !META_PHONE_NUMBER_ID.matcher(req.metaPhoneNumberId()).matches()) {
            throw AdminApiException.badRequest("bad_meta_phone_number_id",
                    "metaPhoneNumberId must be the numeric phone-number id returned by Meta");
        }
        if (req.business() == null) {
            throw AdminApiException.badRequest("missing_business_profile", "businessProfile required");
        }
        // phoneE164 + displayName are validated by the sender service with
        // the exact same rules as the manual wizard.
    }

    private static String businessName(OnboardingRequest req) {
        String legal = req.business() == null ? null : req.business().legalName();
        return legal == null || legal.isBlank() ? req.displayName() : legal;
    }

    private static String idempotencyKey(Ids.TenantId tenant, String phoneE164, String wabaId) {
        return tenant.value() + "|" + phoneE164 + "|" + wabaId;
    }

    private WhatsAppSender senderOf(Ids.TenantId tenant, WhatsAppOnboardingAttemptEntity attempt) {
        if (attempt.getSenderId() == null) return null;
        return senders.findByPhoneInTenant(tenant, attempt.getPhoneE164()).orElse(null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    static OnboardingAttempt toDomain(WhatsAppOnboardingAttemptEntity e) {
        return new OnboardingAttempt(
                e.getId(),
                new Ids.TenantId(e.getTenantId()),
                e.getPhoneE164(),
                e.getWabaId(),
                e.getMetaPhoneNumberId(),
                e.getSenderId(),
                e.getMessagingAccountId(),
                OnboardingAttemptState.valueOf(e.getState()),
                e.getFailureCode(),
                e.getFailureMessage(),
                toInstant(e.getCreatedAt()),
                toInstant(e.getUpdatedAt()),
                toInstant(e.getCompletedAt()));
    }

    private static Instant toInstant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }
}
