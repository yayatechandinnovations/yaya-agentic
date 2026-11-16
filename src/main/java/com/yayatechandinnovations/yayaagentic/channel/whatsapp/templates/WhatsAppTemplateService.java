package com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.channel.ProviderCreds;
import com.yayatechandinnovations.yayaagentic.channel.ProviderCredsResolver;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup.WhatsAppMessagingAccount;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup.WhatsAppMessagingAccountService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioMessagingChannelProvider;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppTemplateEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppTemplateHolderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppTemplateHolderEventEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppTemplateHolderEventRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppTemplateHolderRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template CRUD + lifecycle arbiter. A template belongs to a WhatsApp account
 * ({@link TemplateAccounts}) and is approved once; the workspaces that hold it
 * may list and send it (whatsapp-shared-templates-design.md §3–§4). Operators
 * (or an integration's provisioning, through {@link #declare}) own the
 * content; the platform talks to Twilio's Content API behind the scenes:
 * {@link #submit} creates the Content resource and requests WhatsApp approval,
 * {@link #syncSender} and the scheduled sweep pull Meta's verdict, and an
 * APPROVED row's ContentSid is what the WhatsApp outreach leg sends
 * (whatsapp-ops-integration-design.md §6.3).
 *
 * <p>Variable layout of a declared template: the body uses {@code {{1}}…{{n}}};
 * button {@code i} carries payload variable {@code {{n+i}}}; a media header's
 * URL ends in variable {@code {{n+b+1}}} ({@code <token>/<filename>}, so it has
 * the file suffix Meta requires).
 */
@Service
public class WhatsAppTemplateService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppTemplateService.class);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\d+)\\}\\}");
    private static final Pattern ADJACENT_VARIABLES = Pattern.compile("\\}\\}[\\s\\p{Punct}]*\\{\\{");
    private static final Pattern TEMPLATE_NAME = Pattern.compile("[a-z0-9_]{1,128}");
    private static final Set<String> HEADER_KINDS = Set.of("NONE", "DOCUMENT", "IMAGE");
    private static final int MAX_BUTTONS = 3;
    private static final int MAX_BUTTON_CHARS = 20;
    private static final String SHARED = "SHARED";
    private static final String UNSHARED = "UNSHARED";

    private final WhatsAppTemplateRepository repo;
    private final WhatsAppTemplateHolderRepository holders;
    private final WhatsAppTemplateHolderEventRepository holderEvents;
    private final WhatsAppSenderService senders;
    private final WhatsAppMessagingAccountService accounts;
    private final TwilioMessagingChannelProvider twilio;
    private final ProviderCredsResolver credsResolver;
    private final ObjectMapper json;
    private final String publicBaseUrl;

    public WhatsAppTemplateService(WhatsAppTemplateRepository repo,
                                   WhatsAppTemplateHolderRepository holders,
                                   WhatsAppTemplateHolderEventRepository holderEvents,
                                   WhatsAppSenderService senders,
                                   WhatsAppMessagingAccountService accounts,
                                   TwilioMessagingChannelProvider twilio,
                                   ProviderCredsResolver credsResolver,
                                   ObjectMapper json,
                                   @Value("${yaya.agentic.channels.twilio.webhook-base-url:${yaya.agentic.public-base-url:}}")
                                   String publicBaseUrl) {
        this.repo = repo;
        this.holders = holders;
        this.holderEvents = holderEvents;
        this.senders = senders;
        this.accounts = accounts;
        this.twilio = twilio;
        this.credsResolver = credsResolver;
        this.json = json;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
    }

    /**
     * Who is asking. A platform admin (or a platform-scope API key) decides
     * which workspace gets which template; a workspace admin only acts on what
     * its workspace already holds.
     */
    public record Caller(boolean platformAdmin, String actor) {
        public Caller {
            actor = actor == null || actor.isBlank() ? "unknown" : actor.strip();
            if (actor.length() > 128) actor = actor.substring(0, 128);
        }
    }

    /** A workspace that holds a template. */
    public record Holder(String tenantId, String grantedBy, Instant grantedAt) {}

    /** A template with every workspace that holds it — the platform-admin view. */
    public record AccountTemplate(WhatsAppTemplate template, List<Holder> holders) {}

    /**
     * A WhatsApp account that can carry templates: the platform's
     * ({@code tenantId} null), or a workspace's own.
     */
    public record Account(String accountKey, String tenantId, String label, String status,
                          long templateCount, long hiddenCount) {}

    /* ------------------------- workspace-facing --------------------------- */

    /** The templates of the sender's account that this workspace holds. */
    public List<WhatsAppTemplate> listForSender(Ids.TenantId tenant, SenderId sender) {
        // The sender service throws 404 on cross-tenant.
        WhatsAppSender s = senders.requireForTenant(tenant, sender);
        return toDomain(repo.findHeld(TemplateAccounts.keyOf(s), tenant.value()));
    }

    @Transactional
    public WhatsAppTemplate create(Ids.TenantId tenant, SenderId sender,
                                   String name, String locale, TemplateCategory category,
                                   String body, Caller caller) {
        WhatsAppSender s = senders.requireForTenant(tenant, sender);
        validate(name, locale, body);
        String accountKey = TemplateAccounts.keyOf(s);
        Optional<WhatsAppTemplateEntity> existing =
                repo.findByAccountKeyAndNameAndLocale(accountKey, name, locale);
        if (existing.isPresent()) {
            if (holders.existsByTemplateIdAndTenantId(existing.get().getId(), tenant.value())) {
                throw AdminApiException.conflict("template_exists",
                        "a template named '" + name + "' for locale '" + locale
                                + "' already exists on this sender");
            }
            if (!mayJoin(accountKey, caller)) throw nameTaken(name, locale);
            throw AdminApiException.conflict("template_exists",
                    "a template named '" + name + "' for locale '" + locale
                            + "' already exists on this WhatsApp account; declare it to use it");
        }
        WhatsAppTemplateEntity entity = new WhatsAppTemplateEntity(
                accountKey, tenant.value(), name, locale,
                category.name(), body, countPlaceholders(body));
        entity.setContentHash(contentHash(entity));
        try {
            entity = repo.saveAndFlush(entity);
        } catch (DataIntegrityViolationException dup) {
            throw AdminApiException.conflict("template_exists",
                    "a template named '" + name + "' for locale '" + locale
                            + "' already exists on this sender");
        }
        hold(entity, tenant.value(), caller);
        return toDomain(entity, false);
    }

    @Transactional
    public WhatsAppTemplate update(Ids.TenantId tenant, SenderId sender, TemplateId id,
                                   String body, TemplateCategory category, Caller caller) {
        WhatsAppTemplateEntity entity = requireHeld(tenant, sender, id);
        TemplateStatus current = TemplateStatus.valueOf(entity.getStatus());
        if (current == TemplateStatus.APPROVED || current == TemplateStatus.SUBMITTED) {
            throw AdminApiException.conflict("not_editable",
                    "approved or submitted templates are immutable; clone with a new name to revise");
        }
        boolean shared = holders.countByTemplateId(entity.getId()) > 1;
        if (shared && !caller.platformAdmin()) {
            throw sharedNotEditable(entity.getName());
        }
        validateBody(body);
        entity.setBody(body);
        entity.setCategory(category.name());
        entity.setPlaceholderCount(countPlaceholders(body));
        // New wording is new content for the provider: the old Content
        // resource (found by name + hash on submit) must not be reused.
        entity.setContentHash(contentHash(entity));
        entity.setBspContentSid(null);
        if (current == TemplateStatus.REJECTED) {
            entity.setStatus(TemplateStatus.DRAFT.name());
            entity.setRejectionReason(null);
        }
        return toDomain(repo.save(entity), shared);
    }

    /**
     * Create or converge a template from a declaration (an integration's
     * catalog, §14.5), for the workspace of {@code sender}
     * (whatsapp-shared-templates-design.md §4.2). Identical content is a no-op
     * whatever the status — and when another workspace of the same account
     * already has it, a platform admin's declaration shares it instead of
     * submitting it again. Changed content on a SUBMITTED/APPROVED template is
     * refused — Meta templates are immutable, so the declarer bumps the name.
     */
    @Transactional
    public WhatsAppTemplate declare(Ids.TenantId tenant, SenderId sender, Declaration d, Caller caller) {
        WhatsAppSender s = senders.requireForTenant(tenant, sender);
        validateDeclaration(d);
        String hash = contentHash(d);
        String accountKey = TemplateAccounts.keyOf(s);
        Optional<WhatsAppTemplateEntity> existing =
                repo.findByAccountKeyAndNameAndLocale(accountKey, d.name(), d.language());
        if (existing.isEmpty()) {
            WhatsAppTemplateEntity e = new WhatsAppTemplateEntity(accountKey, tenant.value(),
                    d.name(), d.language(), d.category().name(), d.body(), countPlaceholders(d.body()));
            applyDeclaration(e, d, hash);
            e = repo.saveAndFlush(e);
            hold(e, tenant.value(), caller);
            return toDomain(e, false);
        }
        WhatsAppTemplateEntity e = existing.get();
        boolean held = holders.existsByTemplateIdAndTenantId(e.getId(), tenant.value());
        // A name another workspace took says nothing about its content or status.
        if (!held && !mayJoin(accountKey, caller)) throw nameTaken(d.name(), d.language());
        if (hash.equals(e.getContentHash())) {
            if (!held) hold(e, tenant.value(), caller);
            return toDomain(e);
        }
        requireEditable(e);
        long others = holders.countByTemplateId(e.getId()) - (held ? 1 : 0);
        if (others > 0 && !caller.platformAdmin()) throw sharedNotEditable(d.name());
        applyDeclaration(e, d, hash);
        e.setStatus(TemplateStatus.DRAFT.name());
        e.setRejectionReason(null);
        e.setBspContentSid(null);
        e = repo.save(e);
        if (!held) hold(e, tenant.value(), caller);
        return toDomain(e);
    }

    /**
     * Register the content with Twilio (Content API) and request WhatsApp
     * approval. Meta's verdict arrives through {@link #syncSender} / the sweep.
     * A template that is already submitted or approved is returned as it is:
     * one template reaches the provider once, whoever holds it. So is a
     * rejected one whose wording has not changed since the rejection.
     */
    @Transactional
    public WhatsAppTemplate submit(Ids.TenantId tenant, SenderId sender, TemplateId id) {
        return submitLocked(requireHeld(tenant, sender, id).getId());
    }

    /** Pull the approval state of every template this workspace holds on the sender's account. */
    @Transactional
    public List<WhatsAppTemplate> syncSender(Ids.TenantId tenant, SenderId sender) {
        WhatsAppSender s = senders.requireForTenant(tenant, sender);
        String accountKey = TemplateAccounts.keyOf(s);
        List<WhatsAppTemplateEntity> rows = repo.findHeld(accountKey, tenant.value());
        syncAll(accountKey, rows);
        return toDomain(rows);
    }

    /**
     * The workspace stops using the template. The row goes only when nobody
     * holds it any more and Meta never saw it approved; an approved template
     * without holders stays in the account for a platform admin to share again.
     */
    @Transactional
    public void delete(Ids.TenantId tenant, SenderId sender, TemplateId id, Caller caller) {
        WhatsAppTemplateEntity entity = requireHeld(tenant, sender, id);
        release(entity, tenant.value(), caller);
        TemplateStatus status = TemplateStatus.valueOf(entity.getStatus());
        if (holders.countByTemplateId(entity.getId()) == 0
                && (status == TemplateStatus.DRAFT || status == TemplateStatus.REJECTED)) {
            repo.delete(entity);
        }
    }

    /**
     * Lookup used by the WhatsApp outreach leg: the approved template of the
     * sender's account, when the sender's workspace holds it.
     */
    public Optional<WhatsAppTemplate> findApproved(Ids.TenantId tenant, WhatsAppSender sender,
                                                   String name, String locale) {
        return repo.findByAccountKeyAndNameAndLocale(TemplateAccounts.keyOf(sender), name, locale)
                .filter(e -> TemplateStatus.APPROVED.name().equals(e.getStatus()))
                .filter(e -> holders.existsByTemplateIdAndTenantId(e.getId(), tenant.value()))
                .map(e -> toDomain(e, false));
    }

    /**
     * Leave templates of the sender's account out of the console lists, or
     * show them again (whatsapp-shared-templates-design.md §10). Display only:
     * who may send a template and its status do not change. The flag belongs
     * to the template, so one that other workspaces hold is a platform admin's
     * to hide — as its wording is theirs to change.
     */
    @Transactional
    public List<WhatsAppTemplate> setHidden(Ids.TenantId tenant, SenderId sender, List<TemplateId> ids,
                                            boolean hidden, Caller caller) {
        WhatsAppSender s = senders.requireForTenant(tenant, sender);
        List<WhatsAppTemplateEntity> rows = pick(repo.findHeld(TemplateAccounts.keyOf(s), tenant.value()), ids);
        List<WhatsAppTemplate> out = toDomain(rows);
        if (!caller.platformAdmin()) {
            out.stream().filter(WhatsAppTemplate::shared).findFirst().ifPresent(t -> {
                throw AdminApiException.conflict("not_hideable", "template '" + t.name()
                        + "' is shared with other workspaces; only a platform admin can hide it");
            });
        }
        rows.forEach(e -> applyHidden(e, hidden, caller));
        return toDomain(repo.saveAll(rows));
    }

    /* -------------------------- platform-facing --------------------------- */

    /** The platform account, then every connected account. */
    public List<Account> listAccounts() {
        List<Account> out = new ArrayList<>();
        out.add(new Account(TemplateAccounts.PLATFORM, null, "Platform account", "ACTIVE",
                repo.countByAccountKey(TemplateAccounts.PLATFORM),
                repo.countByAccountKeyAndHiddenAtIsNotNull(TemplateAccounts.PLATFORM)));
        for (WhatsAppMessagingAccount a : accounts.listAll()) {
            out.add(new Account(a.id(), a.tenant().value(), a.friendlyName(), a.status().name(),
                    repo.countByAccountKey(a.id()), repo.countByAccountKeyAndHiddenAtIsNotNull(a.id())));
        }
        return out;
    }

    public List<AccountTemplate> listForAccount(String accountKey) {
        requireAccount(accountKey);
        return withHolders(repo.findByAccountKeyOrderByNameAscLocaleAsc(accountKey));
    }

    /** Declare a template of the account itself; no workspace holds it until it is shared. */
    @Transactional
    public AccountTemplate declareForAccount(String accountKey, Declaration d) {
        requireAccount(accountKey);
        validateDeclaration(d);
        String hash = contentHash(d);
        Optional<WhatsAppTemplateEntity> existing =
                repo.findByAccountKeyAndNameAndLocale(accountKey, d.name(), d.language());
        WhatsAppTemplateEntity e;
        if (existing.isEmpty()) {
            e = new WhatsAppTemplateEntity(accountKey, null, d.name(), d.language(),
                    d.category().name(), d.body(), countPlaceholders(d.body()));
            applyDeclaration(e, d, hash);
            e = repo.saveAndFlush(e);
        } else {
            e = existing.get();
            if (!hash.equals(e.getContentHash())) {
                requireEditable(e);
                applyDeclaration(e, d, hash);
                e.setStatus(TemplateStatus.DRAFT.name());
                e.setRejectionReason(null);
                e.setBspContentSid(null);
                e = repo.save(e);
            }
        }
        return withHolders(List.of(e)).get(0);
    }

    @Transactional
    public AccountTemplate submitForAccount(String accountKey, TemplateId id) {
        WhatsAppTemplateEntity e = requireInAccount(accountKey, id);
        submitLocked(e.getId());
        return withHolders(List.of(e)).get(0);
    }

    @Transactional
    public List<AccountTemplate> syncAccount(String accountKey) {
        requireAccount(accountKey);
        List<WhatsAppTemplateEntity> rows = repo.findByAccountKeyOrderByNameAscLocaleAsc(accountKey);
        syncAll(accountKey, rows);
        return withHolders(rows);
    }

    /** Let a workspace use a template (§4.1: an own account is never shared outside its workspace). */
    @Transactional
    public AccountTemplate share(String accountKey, TemplateId id, Ids.TenantId tenant, Caller caller) {
        Optional<WhatsAppMessagingAccount> own = requireAccount(accountKey);
        if (own.isPresent() && !own.get().tenant().equals(tenant)) {
            throw AdminApiException.conflict("holder_not_allowed",
                    "this WhatsApp account belongs to one workspace; its templates cannot be shared with another");
        }
        WhatsAppTemplateEntity e = requireInAccount(accountKey, id);
        if (!holders.existsByTemplateIdAndTenantId(e.getId(), tenant.value())) {
            hold(e, tenant.value(), caller);
        }
        return withHolders(List.of(e)).get(0);
    }

    @Transactional
    public AccountTemplate unshare(String accountKey, TemplateId id, Ids.TenantId tenant, Caller caller) {
        WhatsAppTemplateEntity e = requireInAccount(accountKey, id);
        if (holders.existsByTemplateIdAndTenantId(e.getId(), tenant.value())) {
            release(e, tenant.value(), caller);
        }
        return withHolders(List.of(e)).get(0);
    }

    /** {@link #setHidden} for any template of the account. */
    @Transactional
    public List<AccountTemplate> setHiddenForAccount(String accountKey, List<TemplateId> ids, boolean hidden,
                                                     Caller caller) {
        requireAccount(accountKey);
        List<WhatsAppTemplateEntity> rows = pick(repo.findByAccountKeyOrderByNameAscLocaleAsc(accountKey), ids);
        rows.forEach(e -> applyHidden(e, hidden, caller));
        return withHolders(repo.saveAll(rows));
    }

    /** Remove a template nobody holds and Meta never approved. */
    @Transactional
    public void deleteForAccount(String accountKey, TemplateId id) {
        WhatsAppTemplateEntity e = requireInAccount(accountKey, id);
        if (holders.countByTemplateId(e.getId()) > 0) {
            throw AdminApiException.conflict("template_in_use",
                    "workspaces still hold this template; stop sharing it first");
        }
        TemplateStatus status = TemplateStatus.valueOf(e.getStatus());
        if (status != TemplateStatus.DRAFT && status != TemplateStatus.REJECTED) {
            throw AdminApiException.conflict("not_deletable",
                    "a template in status " + status + " exists on WhatsApp's side and stays in the account");
        }
        repo.delete(e);
    }

    /* ------------------------------ lifecycle ----------------------------- */

    private WhatsAppTemplate submitLocked(Long id) {
        WhatsAppTemplateEntity entity = repo.lockById(id).orElseThrow(() ->
                AdminApiException.notFound("template_not_found", "no such template " + id));
        TemplateStatus status = TemplateStatus.valueOf(entity.getStatus());
        if (status == TemplateStatus.SUBMITTED || status == TemplateStatus.APPROVED) {
            return toDomain(entity);
        }
        if (!status.canSubmit()) {
            throw AdminApiException.conflict("not_submittable",
                    "template in status " + status + " cannot be submitted");
        }
        // Rejected and not reworded since (an edit clears the content sid):
        // WhatsApp already answered for exactly this content, and the provider
        // refuses a second approval request for it. The verdict stands.
        if (status == TemplateStatus.REJECTED && entity.getBspContentSid() != null
                && !entity.getBspContentSid().isBlank()) {
            return toDomain(entity);
        }
        if (!credsResolver.isReady()) {
            throw AdminApiException.conflict("provider_not_configured",
                    "Twilio is not configured on this platform instance");
        }
        try {
            ProviderCreds creds = credsFor(entity.getAccountKey());
            Map<String, Object> content = contentDefinition(entity);
            String friendly = String.valueOf(content.get("friendly_name"));
            String sid = twilio.findContentByName(friendly, creds)
                    .orElseGet(() -> twilio.createContent(content, creds));
            entity.setBspContentSid(sid);
            var state = twilio.requestWhatsAppApproval(sid, entity.getName(),
                    entity.getCategory(), creds);
            applyApproval(entity, state);
        } catch (AdminApiException e) {
            throw e;
        } catch (ProviderCredsResolver.ProviderNotConfiguredException e) {
            throw AdminApiException.conflict("provider_not_configured", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Template {} submit to Twilio failed: {}", entity.getName(), e.getMessage());
            throw AdminApiException.conflict("submit_failed",
                    "Twilio rejected the template: " + e.getMessage());
        }
        entity.setSubmittedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return toDomain(repo.save(entity));
    }

    /** Meta reviews in minutes to a day; poll what is still waiting (and catch pauses). */
    @Scheduled(fixedDelayString = "${yaya.agentic.channels.whatsapp.template-sync-ms:600000}",
            initialDelayString = "${yaya.agentic.channels.whatsapp.template-sync-initial-ms:120000}")
    @Transactional
    public void syncPending() {
        if (!credsResolver.isReady()) return;
        Map<String, List<WhatsAppTemplateEntity>> byAccount = new LinkedHashMap<>();
        for (WhatsAppTemplateEntity e : repo.findByStatusIn(List.of(
                TemplateStatus.SUBMITTED.name(), TemplateStatus.APPROVED.name()))) {
            byAccount.computeIfAbsent(e.getAccountKey(), k -> new ArrayList<>()).add(e);
        }
        byAccount.forEach(this::syncAll);
    }

    /** One credential lookup per account; an account that cannot speak is skipped. */
    private void syncAll(String accountKey, List<WhatsAppTemplateEntity> rows) {
        if (rows.isEmpty() || !credsResolver.isReady()) return;
        ProviderCreds creds;
        try {
            creds = credsFor(accountKey);
        } catch (RuntimeException ex) {
            log.debug("template sync skipped for account {}: {}", accountKey, ex.getMessage());
            return;
        }
        for (WhatsAppTemplateEntity e : rows) syncOne(e, creds);
    }

    private void syncOne(WhatsAppTemplateEntity e, ProviderCreds creds) {
        if (e.getBspContentSid() == null || e.getBspContentSid().isBlank()) return;
        try {
            applyApproval(e, twilio.fetchWhatsAppApproval(e.getBspContentSid(), creds));
            e.setLastSyncAt(OffsetDateTime.now(ZoneOffset.UTC));
            repo.save(e);
        } catch (RuntimeException ex) {
            log.warn("Template {} approval sync failed: {}", e.getName(), ex.getMessage());
        }
    }

    private ProviderCreds credsFor(String accountKey) {
        return credsResolver.resolveForAccount(TemplateAccounts.messagingAccountIdOf(accountKey));
    }

    /* ------------------------------- holders ------------------------------ */

    private void hold(WhatsAppTemplateEntity e, String tenantId, Caller caller) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        holders.save(new WhatsAppTemplateHolderEntity(e.getId(), tenantId, caller.actor(), now));
        recordHolderEvent(e, tenantId, SHARED, caller, now);
    }

    private void release(WhatsAppTemplateEntity e, String tenantId, Caller caller) {
        holders.deleteByTemplateIdAndTenantId(e.getId(), tenantId);
        holders.flush();
        recordHolderEvent(e, tenantId, UNSHARED, caller, OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void recordHolderEvent(WhatsAppTemplateEntity e, String tenantId, String action,
                                   Caller caller, OffsetDateTime at) {
        holderEvents.save(new WhatsAppTemplateHolderEventEntity(e.getId(), e.getAccountKey(),
                e.getName(), e.getLocale(), tenantId, action, caller.actor(), at));
        log.info("WhatsApp template {} ({}) on account {}: {} workspace {} by {}",
                e.getName(), e.getLocale(), e.getAccountKey(), action, tenantId, caller.actor());
    }

    /** The requested rows out of {@code candidates}, all or nothing. */
    private static List<WhatsAppTemplateEntity> pick(List<WhatsAppTemplateEntity> candidates, List<TemplateId> ids) {
        if (ids == null || ids.isEmpty()) {
            throw AdminApiException.badRequest("missing_template_ids", "templateIds required");
        }
        Map<Long, WhatsAppTemplateEntity> byId = new LinkedHashMap<>();
        for (WhatsAppTemplateEntity e : candidates) byId.put(e.getId(), e);
        List<WhatsAppTemplateEntity> out = new ArrayList<>();
        for (Long id : ids.stream().map(TemplateId::value).distinct().toList()) {
            WhatsAppTemplateEntity e = byId.get(id);
            if (e == null) throw AdminApiException.notFound("template_not_found", "no such template " + id);
            out.add(e);
        }
        return out;
    }

    /** Hiding what is already hidden keeps who hid it and when. */
    private static void applyHidden(WhatsAppTemplateEntity e, boolean hidden, Caller caller) {
        if (hidden == (e.getHiddenAt() != null)) return;
        e.setHiddenAt(hidden ? OffsetDateTime.now(ZoneOffset.UTC) : null);
        e.setHiddenBy(hidden ? caller.actor() : null);
    }

    /** The template, when it lives on the sender's account and the workspace holds it. */
    private WhatsAppTemplateEntity requireHeld(Ids.TenantId tenant, SenderId sender, TemplateId id) {
        WhatsAppSender s = senders.requireForTenant(tenant, sender);
        return repo.findByAccountKeyAndId(TemplateAccounts.keyOf(s), id.value())
                .filter(e -> holders.existsByTemplateIdAndTenantId(e.getId(), tenant.value()))
                .orElseThrow(() -> AdminApiException.notFound("template_not_found",
                        "no such template " + id.value()));
    }

    private WhatsAppTemplateEntity requireInAccount(String accountKey, TemplateId id) {
        requireAccount(accountKey);
        return repo.findByAccountKeyAndId(accountKey, id.value())
                .orElseThrow(() -> AdminApiException.notFound("template_not_found",
                        "no such template " + id.value()));
    }

    /** @return the workspace's own account, or empty for the platform account */
    private Optional<WhatsAppMessagingAccount> requireAccount(String accountKey) {
        if (TemplateAccounts.isPlatform(accountKey)) return Optional.empty();
        return Optional.of(accounts.findById(accountKey)
                .orElseThrow(() -> AdminApiException.notFound("whatsapp_account_not_found",
                        "no such WhatsApp account '" + accountKey + "'")));
    }

    /**
     * May the caller's workspace start using a template it does not hold? On
     * the platform account that is a platform admin's decision. A workspace's
     * own account only ever serves that workspace, so there it always may —
     * e.g. it takes back a template it stopped using.
     */
    private static boolean mayJoin(String accountKey, Caller caller) {
        return caller.platformAdmin() || !TemplateAccounts.isPlatform(accountKey);
    }

    private static void requireEditable(WhatsAppTemplateEntity e) {
        TemplateStatus current = TemplateStatus.valueOf(e.getStatus());
        if (current == TemplateStatus.APPROVED || current == TemplateStatus.SUBMITTED
                || current == TemplateStatus.PAUSED) {
            throw AdminApiException.conflict("not_editable",
                    "template '" + e.getName() + "' is " + current
                            + " with different content; declare it under a new name (e.g. _v2)");
        }
    }

    private static AdminApiException nameTaken(String name, String locale) {
        return AdminApiException.conflict("template_name_taken",
                "the name '" + name + "' (" + locale + ") is already used on this WhatsApp account; "
                        + "a platform admin can share that template with this workspace");
    }

    private static AdminApiException sharedNotEditable(String name) {
        return AdminApiException.conflict("not_editable",
                "template '" + name + "' is shared with other workspaces; only a platform admin can change it");
    }

    private static void applyApproval(WhatsAppTemplateEntity e, TwilioMessagingChannelProvider.ApprovalState state) {
        String st = state.status() == null ? "" : state.status().toLowerCase(java.util.Locale.ROOT);
        switch (st) {
            case "approved" -> {
                if (!TemplateStatus.APPROVED.name().equals(e.getStatus())) {
                    e.setApprovedAt(OffsetDateTime.now(ZoneOffset.UTC));
                }
                e.setStatus(TemplateStatus.APPROVED.name());
                e.setRejectionReason(null);
            }
            case "rejected" -> {
                e.setStatus(TemplateStatus.REJECTED.name());
                e.setRejectionReason(state.rejectionReason());
            }
            case "paused", "disabled" -> {
                e.setStatus(TemplateStatus.PAUSED.name());
                e.setRejectionReason(state.rejectionReason());
            }
            case "unsubmitted" -> { /* nothing on Twilio's side yet */ }
            default -> e.setStatus(TemplateStatus.SUBMITTED.name());
        }
    }

    /**
     * The Twilio Content definition (§6.3). Text-only → {@code twilio/text};
     * buttons without media → {@code twilio/quick-reply}; a media header →
     * {@code twilio/card} (the body becomes the card title, WhatsApp's body).
     */
    Map<String, Object> contentDefinition(WhatsAppTemplateEntity e) {
        int n = e.getPlaceholderCount();
        List<String> buttons = readList(e.getButtonsJson());
        Map<String, Object> samples = readMap(e.getSamplesJson());
        List<String> bodySamples = new ArrayList<>();
        if (samples.get("body") instanceof List<?> l) for (Object o : l) bodySamples.add(String.valueOf(o));

        Map<String, String> variables = new LinkedHashMap<>();
        for (int i = 1; i <= n; i++) {
            variables.put(String.valueOf(i), i <= bodySamples.size() ? bodySamples.get(i - 1) : "ejemplo");
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i++) {
            String var = String.valueOf(n + i + 1);
            variables.put(var, "act:sample" + (i + 1));
            actions.add(new LinkedHashMap<>(Map.of("type", "QUICK_REPLY",
                    "title", buttons.get(i), "id", "{{" + var + "}}")));
        }
        Map<String, Object> types = new LinkedHashMap<>();
        String header = e.getHeaderKind() == null ? "NONE" : e.getHeaderKind();
        if (!"NONE".equals(header)) {
            if (publicBaseUrl.isBlank()) {
                throw AdminApiException.conflict("public_base_url_unset",
                        "a media template needs yaya.agentic.public-base-url so Twilio can fetch the file");
            }
            String var = String.valueOf(n + buttons.size() + 1);
            String filename = samples.get("mediaFilename") == null ? "sample.pdf"
                    : String.valueOf(samples.get("mediaFilename"));
            variables.put(var, "sample/" + filename);
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("title", e.getBody());
            card.put("media", List.of(publicBaseUrl + "/v1/media/{{" + var + "}}"));
            if (!actions.isEmpty()) card.put("actions", actions);
            types.put("twilio/card", card);
        } else if (!actions.isEmpty()) {
            List<Map<String, Object>> qr = new ArrayList<>();
            for (Map<String, Object> a : actions) qr.add(Map.of("title", a.get("title"), "id", a.get("id")));
            types.put("twilio/quick-reply", Map.of("body", e.getBody(), "actions", qr));
        } else {
            types.put("twilio/text", Map.of("body", e.getBody()));
        }
        Map<String, Object> content = new LinkedHashMap<>();
        String hash = e.getContentHash() == null ? "" : e.getContentHash().substring(0, Math.min(8, e.getContentHash().length()));
        content.put("friendly_name", e.getName() + "_" + e.getLocale() + (hash.isEmpty() ? "" : "_" + hash));
        content.put("language", e.getLocale());
        content.put("variables", variables);
        content.put("types", types);
        return content;
    }


    /* ---------------------------- helpers --------------------------------- */

    /** A declared template (§14.5). */
    public record Declaration(String name, String language, TemplateCategory category, String header,
                              String body, List<String> buttons, List<String> bodySamples,
                              String mediaFilename) {
        public Declaration {
            language = language == null || language.isBlank() ? "es" : language.trim();
            category = category == null ? TemplateCategory.UTILITY : category;
            header = header == null || header.isBlank() ? "NONE" : header.trim().toUpperCase(java.util.Locale.ROOT);
            buttons = buttons == null ? List.of() : List.copyOf(buttons);
            bodySamples = bodySamples == null ? List.of() : List.copyOf(bodySamples);
        }
    }

    private void applyDeclaration(WhatsAppTemplateEntity e, Declaration d, String hash) {
        e.setBody(d.body());
        e.setCategory(d.category().name());
        e.setPlaceholderCount(countPlaceholders(d.body()));
        e.setHeaderKind(d.header());
        e.setButtonsJson(write(d.buttons()));
        Map<String, Object> samples = new LinkedHashMap<>();
        samples.put("body", d.bodySamples());
        if (d.mediaFilename() != null) samples.put("mediaFilename", d.mediaFilename());
        e.setSamplesJson(write(samples));
        e.setContentHash(hash);
    }

    /** Meta's template rules, checked before anything reaches Twilio. */
    static void validateDeclaration(Declaration d) {
        if (d == null) throw AdminApiException.badRequest("missing_body", "declaration required");
        if (d.name() == null || !TEMPLATE_NAME.matcher(d.name()).matches()) {
            throw AdminApiException.badRequest("bad_name", "name: lowercase letters, digits and _ only");
        }
        validateBody(d.body());
        if (!HEADER_KINDS.contains(d.header())) {
            throw AdminApiException.badRequest("bad_header", "header must be NONE, DOCUMENT or IMAGE");
        }
        if (d.buttons().size() > MAX_BUTTONS) {
            throw AdminApiException.badRequest("bad_buttons", "at most " + MAX_BUTTONS + " buttons");
        }
        for (String b : d.buttons()) {
            if (b == null || b.isBlank() || b.length() > MAX_BUTTON_CHARS) {
                throw AdminApiException.badRequest("bad_buttons",
                        "button labels are 1–" + MAX_BUTTON_CHARS + " characters");
            }
        }
        int n = countPlaceholders(d.body());
        Matcher m = PLACEHOLDER.matcher(d.body());
        Set<Integer> seen = new java.util.TreeSet<>();
        while (m.find()) seen.add(Integer.parseInt(m.group(1)));
        if (seen.size() != n || (n > 0 && !seen.contains(1))) {
            throw AdminApiException.badRequest("bad_placeholders", "placeholders must be {{1}}…{{n}} without gaps");
        }
        String trimmed = d.body().strip();
        if (trimmed.startsWith("{{") || trimmed.endsWith("}}")) {
            throw AdminApiException.badRequest("bad_placeholders", "the body may not start or end with a variable");
        }
        if (ADJACENT_VARIABLES.matcher(d.body()).find()) {
            throw AdminApiException.badRequest("bad_placeholders", "variables may not be adjacent");
        }
        if (d.bodySamples().size() != n) {
            throw AdminApiException.badRequest("bad_samples", "samples.body needs exactly " + n + " values");
        }
        if (!"NONE".equals(d.header()) && (d.mediaFilename() == null || !d.mediaFilename().contains("."))) {
            throw AdminApiException.badRequest("bad_samples", "a media header needs samples.mediaFilename with a suffix");
        }
    }

    private static String contentHash(Declaration d) {
        return contentHash(d.language(), d.category().name(), d.header(), d.body(), d.buttons(),
                d.bodySamples(), d.mediaFilename());
    }

    /** Same digest as a declaration of the row's content, so equal content always compares equal. */
    private String contentHash(WhatsAppTemplateEntity e) {
        Map<String, Object> samples = readMap(e.getSamplesJson());
        List<String> bodySamples = new ArrayList<>();
        if (samples.get("body") instanceof List<?> l) for (Object o : l) bodySamples.add(String.valueOf(o));
        Object media = samples.get("mediaFilename");
        return contentHash(e.getLocale(), e.getCategory(),
                e.getHeaderKind() == null ? "NONE" : e.getHeaderKind(), e.getBody(),
                readList(e.getButtonsJson()), bodySamples, media == null ? null : String.valueOf(media));
    }

    private static String contentHash(String language, String category, String header, String body,
                                      List<String> buttons, List<String> bodySamples, String mediaFilename) {
        String canonical = String.join("\u0001", language, category, header, body,
                String.join("\u0002", buttons), String.join("\u0002", bodySamples),
                mediaFilename == null ? "" : mediaFilename);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> readList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> readMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            return json.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static int countPlaceholders(String body) {
        Matcher m = PLACEHOLDER.matcher(body);
        int max = 0;
        while (m.find()) {
            try {
                int idx = Integer.parseInt(m.group(1));
                max = Math.max(max, idx);
            } catch (NumberFormatException ignored) { /* skip */ }
        }
        return max;
    }

    private static void validate(String name, String locale, String body) {
        if (name == null || name.isBlank()) {
            throw AdminApiException.badRequest("bad_name", "template name required");
        }
        if (locale == null || locale.isBlank()) {
            throw AdminApiException.badRequest("bad_locale", "template locale required");
        }
        validateBody(body);
    }

    private static void validateBody(String body) {
        if (body == null || body.isBlank()) {
            throw AdminApiException.badRequest("bad_body", "template body required");
        }
        if (body.length() > 1024) {
            throw AdminApiException.badRequest("bad_body",
                    "template body must be ≤ 1024 characters");
        }
    }

    /** With {@code shared} looked up; use the two-argument form when it is already known. */
    private WhatsAppTemplate toDomain(WhatsAppTemplateEntity entity) {
        return toDomain(entity, holders.countByTemplateId(entity.getId()) > 1);
    }

    private List<WhatsAppTemplate> toDomain(List<WhatsAppTemplateEntity> rows) {
        Map<Long, Integer> counts = new HashMap<>();
        for (WhatsAppTemplateHolderEntity h : holdersOf(rows)) counts.merge(h.getTemplateId(), 1, Integer::sum);
        return rows.stream().map(e -> toDomain(e, counts.getOrDefault(e.getId(), 0) > 1)).toList();
    }

    private List<AccountTemplate> withHolders(List<WhatsAppTemplateEntity> rows) {
        Map<Long, List<Holder>> byTemplate = new HashMap<>();
        for (WhatsAppTemplateHolderEntity h : holdersOf(rows)) {
            byTemplate.computeIfAbsent(h.getTemplateId(), k -> new ArrayList<>())
                    .add(new Holder(h.getTenantId(), h.getGrantedBy(), toInstant(h.getGrantedAt())));
        }
        List<AccountTemplate> out = new ArrayList<>();
        for (WhatsAppTemplateEntity e : rows) {
            List<Holder> hs = byTemplate.getOrDefault(e.getId(), List.of()).stream()
                    .sorted(java.util.Comparator.comparing(Holder::tenantId)).toList();
            out.add(new AccountTemplate(toDomain(e, hs.size() > 1), hs));
        }
        return out;
    }

    private List<WhatsAppTemplateHolderEntity> holdersOf(List<WhatsAppTemplateEntity> rows) {
        if (rows.isEmpty()) return List.of();
        return holders.findByTemplateIdIn(rows.stream().map(WhatsAppTemplateEntity::getId).toList());
    }

    private WhatsAppTemplate toDomain(WhatsAppTemplateEntity entity, boolean shared) {
        return new WhatsAppTemplate(
                new TemplateId(entity.getId()),
                entity.getAccountKey(),
                entity.getName(),
                entity.getLocale(),
                TemplateCategory.valueOf(entity.getCategory()),
                entity.getBody(),
                entity.getPlaceholderCount(),
                entity.getBspTemplateId(),
                entity.getBspContentSid(),
                TemplateStatus.valueOf(entity.getStatus()),
                entity.getRejectionReason(),
                toInstant(entity.getSubmittedAt()),
                toInstant(entity.getApprovedAt()),
                toInstant(entity.getLastSyncAt()),
                entity.getHeaderKind(),
                readList(entity.getButtonsJson()),
                toInstant(entity.getHiddenAt()),
                entity.getHiddenBy(),
                shared
        );
    }

    private static Instant toInstant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }
}
