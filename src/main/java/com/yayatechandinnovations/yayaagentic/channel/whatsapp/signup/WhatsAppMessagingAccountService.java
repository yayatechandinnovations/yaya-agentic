package com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup;

import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup.TwilioSubaccountProvisioner.SubaccountProvisionResult;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppMessagingAccountEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppMessagingAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Messaging-account CRUD + subaccount provisioning arbiter. One account per
 * (tenant, WABA); the WABA's global uniqueness (V75 constraint) is the
 * cross-tenant ownership lock. Provisioning follows the codebase's
 * two-transaction split: the row is committed as PROVISIONING before the
 * Twilio call, and the result folds back in a second transaction — the slow
 * remote call never holds a row lock, and a crash mid-call leaves a
 * resumable PROVISIONING row instead of a duplicate subaccount.
 */
@Service
public class WhatsAppMessagingAccountService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppMessagingAccountService.class);

    private final WhatsAppMessagingAccountRepository repo;
    private final TwilioSubaccountProvisioner provisioner;
    private final JwtSecretCipher cipher;

    public WhatsAppMessagingAccountService(WhatsAppMessagingAccountRepository repo,
                                           TwilioSubaccountProvisioner provisioner,
                                           JwtSecretCipher cipher) {
        this.repo = repo;
        this.provisioner = provisioner;
        this.cipher = cipher;
    }

    /* ---------------------------- read ------------------------------------ */

    public List<WhatsAppMessagingAccount> listForTenant(Ids.TenantId tenant) {
        return repo.findByTenantIdOrderByCreatedAtDesc(tenant.value()).stream()
                .map(WhatsAppMessagingAccountService::toDomain)
                .toList();
    }

    public WhatsAppMessagingAccount requireForTenant(Ids.TenantId tenant, String id) {
        return repo.findByTenantIdAndId(tenant.value(), id)
                .map(WhatsAppMessagingAccountService::toDomain)
                .orElseThrow(() -> AdminApiException.notFound("messaging_account_not_found",
                        "no such WhatsApp messaging account '" + id + "' in tenant '"
                                + tenant.value() + "'"));
    }

    public Optional<WhatsAppMessagingAccount> findById(String id) {
        return repo.findById(id).map(WhatsAppMessagingAccountService::toDomain);
    }

    /** Every connected account, for the platform-admin template view. */
    public List<WhatsAppMessagingAccount> listAll() {
        return repo.findAll().stream()
                .map(WhatsAppMessagingAccountService::toDomain)
                .sorted(java.util.Comparator.comparing(WhatsAppMessagingAccount::createdAt))
                .toList();
    }

    /** Cross-tenant WABA lookup backing the ownership-conflict check. */
    public Optional<WhatsAppMessagingAccount> findByWaba(String wabaId) {
        return repo.findByWabaId(wabaId).map(WhatsAppMessagingAccountService::toDomain);
    }

    /* ---------------------------- provision ------------------------------- */

    /**
     * Find the tenant's account for this WABA, or provision a new dedicated
     * subaccount. Idempotent: a WABA already mapped to this tenant is reused
     * (including a half-provisioned PROVISIONING row, which is resumed); a
     * WABA mapped to another tenant is a 409.
     */
    public WhatsAppMessagingAccount getOrProvision(Ids.TenantId tenant, String wabaId,
                                                   String businessName) {
        Optional<WhatsAppMessagingAccountEntity> existing = repo.findByWabaId(wabaId);
        if (existing.isPresent()) {
            WhatsAppMessagingAccountEntity e = existing.get();
            if (!e.getTenantId().equals(tenant.value())) {
                throw AdminApiException.conflict("waba_owned_by_other_tenant",
                        "this WhatsApp Business Account is already connected to a different workspace");
            }
            if (MessagingAccountStatus.valueOf(e.getStatus()) == MessagingAccountStatus.DISCONNECTED) {
                throw AdminApiException.conflict("messaging_account_disconnected",
                        "this WhatsApp Business Account was disconnected — reconnect it before adding senders");
            }
            if (e.getTwilioSubaccountSid() != null) {
                return toDomain(e);
            }
            // PROVISIONING row without a subaccount (earlier attempt died
            // mid-call) — resume by provisioning into the same row.
            return completeProvisioning(tenant, e.getId(), businessName);
        }

        if (!cipher.isConfigured()) {
            throw new AdminApiException(HttpStatus.SERVICE_UNAVAILABLE, "secrets_cipher_unconfigured",
                    "credential encryption is not configured on this instance "
                            + "(yaya.agentic.secrets.encryption-key)");
        }
        if (!provisioner.isReady()) {
            throw AdminApiException.conflict("provider_not_ready",
                    "WhatsApp provisioning is not configured on this instance.");
        }

        // tx 1 — claim the WABA. The UNIQUE(waba_id) constraint collapses a
        // concurrent claim race to a single winner.
        WhatsAppMessagingAccountEntity claimed;
        try {
            claimed = claimWaba(tenant, wabaId, businessName);
        } catch (DataIntegrityViolationException race) {
            WhatsAppMessagingAccountEntity winner = repo.findByWabaId(wabaId).orElseThrow();
            if (!winner.getTenantId().equals(tenant.value())) {
                throw AdminApiException.conflict("waba_owned_by_other_tenant",
                        "this WhatsApp Business Account is already connected to a different workspace");
            }
            claimed = winner;
        }
        if (claimed.getTwilioSubaccountSid() != null) {
            return toDomain(claimed);
        }
        return completeProvisioning(tenant, claimed.getId(), businessName);
    }

    @Transactional
    protected WhatsAppMessagingAccountEntity claimWaba(Ids.TenantId tenant, String wabaId,
                                                       String businessName) {
        WhatsAppMessagingAccountEntity entity = new WhatsAppMessagingAccountEntity(
                "wma_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24),
                tenant.value(),
                wabaId,
                friendlyName(tenant, businessName),
                MessagingAccountStatus.PROVISIONING.name());
        return repo.saveAndFlush(entity);
    }

    /** Outside any transaction: call Twilio, then fold the result in tx 2. */
    private WhatsAppMessagingAccount completeProvisioning(Ids.TenantId tenant, String accountId,
                                                          String businessName) {
        WhatsAppMessagingAccountEntity entity = repo.findByTenantIdAndId(tenant.value(), accountId)
                .orElseThrow();
        SubaccountProvisionResult result = provisioner.provision(entity.getFriendlyName());
        return switch (result) {
            case SubaccountProvisionResult.Created created ->
                    applyProvisioned(tenant, accountId, created);
            case SubaccountProvisionResult.Rejected rejected -> {
                log.warn("Twilio subaccount create rejected tenant={} account={} code={}",
                        tenant.value(), accountId, rejected.code());
                throw AdminApiException.conflict("subaccount_rejected", rejected.message());
            }
            case SubaccountProvisionResult.TransientFailure transientFailure -> {
                log.warn("Twilio subaccount create transient failure tenant={} account={} code={}",
                        tenant.value(), accountId, transientFailure.code());
                throw new AdminApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "subaccount_provisioning_unavailable",
                        "could not reach the messaging provider — retry the connection shortly");
            }
        };
    }

    @Transactional
    protected WhatsAppMessagingAccount applyProvisioned(Ids.TenantId tenant, String accountId,
                                                        SubaccountProvisionResult.Created created) {
        WhatsAppMessagingAccountEntity entity = repo.findByTenantIdAndId(tenant.value(), accountId)
                .orElseThrow();
        JwtSecretCipher.Encrypted enc = cipher.encrypt(created.authToken());
        entity.setTwilioSubaccountSid(created.subaccountSid());
        entity.setAuthTokenCiphertext(enc.ciphertext());
        entity.setAuthTokenIv(enc.iv());
        entity.setStatus(MessagingAccountStatus.ACTIVE.name());
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        WhatsAppMessagingAccountEntity saved = repo.save(entity);
        log.info("WhatsApp messaging account ACTIVE tenant={} account={} subaccount=…{}",
                tenant.value(), accountId, sidSuffix(created.subaccountSid()));
        return toDomain(saved);
    }

    /* ---------------------------- secrets --------------------------------- */

    /**
     * Unwrap the subaccount auth token. Used by the creds resolver (API
     * calls) and the webhook edge (signature verification). Never log the
     * return value.
     */
    public String decryptAuthToken(String accountId) {
        WhatsAppMessagingAccountEntity entity = repo.findById(accountId)
                .orElseThrow(() -> AdminApiException.notFound("messaging_account_not_found",
                        "no such WhatsApp messaging account '" + accountId + "'"));
        if (entity.getAuthTokenCiphertext() == null || entity.getAuthTokenIv() == null) {
            throw AdminApiException.conflict("messaging_account_not_provisioned",
                    "messaging account has no provisioned credentials yet");
        }
        return cipher.decrypt(entity.getAuthTokenCiphertext(), entity.getAuthTokenIv());
    }

    /* ---------------------------- disconnect ------------------------------ */

    /**
     * Operator-driven disconnect. Local-first: the account stops resolving
     * credentials immediately; no external Twilio resources are deleted (the
     * sender-level revoke is the explicit external-teardown surface). The row
     * is kept as an audit record.
     */
    @Transactional
    public WhatsAppMessagingAccount disconnect(Ids.TenantId tenant, String accountId) {
        WhatsAppMessagingAccountEntity entity = repo.findByTenantIdAndId(tenant.value(), accountId)
                .orElseThrow(() -> AdminApiException.notFound("messaging_account_not_found",
                        "no such WhatsApp messaging account '" + accountId + "' in tenant '"
                                + tenant.value() + "'"));
        entity.setStatus(MessagingAccountStatus.DISCONNECTED.name());
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        log.info("WhatsApp messaging account DISCONNECTED tenant={} account={} subaccount=…{}",
                tenant.value(), accountId, sidSuffix(entity.getTwilioSubaccountSid()));
        return toDomain(repo.save(entity));
    }

    /* ---------------------------- helpers --------------------------------- */

    private static String friendlyName(Ids.TenantId tenant, String businessName) {
        String base = "yaya:" + tenant.value() + ":"
                + (businessName == null ? "" : businessName.trim());
        return base.length() > 128 ? base.substring(0, 128) : base;
    }

    private static String sidSuffix(String sid) {
        if (sid == null || sid.length() < 4) return "????";
        return sid.substring(sid.length() - 4);
    }

    static WhatsAppMessagingAccount toDomain(WhatsAppMessagingAccountEntity e) {
        return new WhatsAppMessagingAccount(
                e.getId(),
                new Ids.TenantId(e.getTenantId()),
                e.getWabaId(),
                e.getTwilioSubaccountSid(),
                e.getFriendlyName(),
                MessagingAccountStatus.valueOf(e.getStatus()),
                toInstant(e.getCreatedAt()),
                toInstant(e.getUpdatedAt()));
    }

    private static Instant toInstant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }
}
