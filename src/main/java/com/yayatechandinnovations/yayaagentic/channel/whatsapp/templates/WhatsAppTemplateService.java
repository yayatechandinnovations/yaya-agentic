package com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates;

import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppTemplateEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppTemplateRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template CRUD + lifecycle arbiter. The Twilio Content API submit/sync
 * loop is the integration analogue of {@link WhatsAppSenderService}'s
 * submit/sync — operator does the CRUD, the platform talks to Twilio
 * behind the scenes.
 *
 * <p>v1 ships local CRUD + an explicit "submit returns 501 — wires up the
 * Twilio Content API in a follow-on" seam analogous to W1's submit. The
 * full Twilio Content API integration is a separable follow-on; this
 * service deliberately stops at the seam so the engine + admin UX can
 * land first.
 */
@Service
public class WhatsAppTemplateService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\d+)\\}\\}");

    private final WhatsAppTemplateRepository repo;
    private final WhatsAppSenderService senders;

    public WhatsAppTemplateService(WhatsAppTemplateRepository repo,
                                   WhatsAppSenderService senders) {
        this.repo = repo;
        this.senders = senders;
    }

    public List<WhatsAppTemplate> listForSender(Ids.TenantId tenant, SenderId sender) {
        // Validate ownership before reading; sender service throws 404 on
        // cross-tenant. We don't actually need the result, just the side
        // effect of the cross-tenant gate.
        senders.requireForTenant(tenant, sender);
        return repo.findByTenantIdAndSenderIdOrderByNameAscLocaleAsc(tenant.value(),
                sender.value()).stream()
                .map(this::toDomain)
                .toList();
    }

    public WhatsAppTemplate requireForTenant(Ids.TenantId tenant, TemplateId id) {
        return repo.findByTenantIdAndId(tenant.value(), id.value())
                .map(this::toDomain)
                .orElseThrow(() -> AdminApiException.notFound("template_not_found",
                        "no such template " + id.value()));
    }

    @Transactional
    public WhatsAppTemplate create(Ids.TenantId tenant, SenderId sender,
                                   String name, String locale, TemplateCategory category,
                                   String body) {
        WhatsAppSender s = senders.requireForTenant(tenant, sender);
        validate(name, locale, body);
        int placeholderCount = countPlaceholders(body);

        WhatsAppTemplateEntity entity = new WhatsAppTemplateEntity(
                s.tenant().value(), s.id().value(), name, locale,
                category.name(), body, placeholderCount);
        try {
            return toDomain(repo.save(entity));
        } catch (DataIntegrityViolationException dup) {
            throw AdminApiException.conflict("template_exists",
                    "a template named '" + name + "' for locale '" + locale
                            + "' already exists on this sender");
        }
    }

    @Transactional
    public WhatsAppTemplate update(Ids.TenantId tenant, TemplateId id,
                                   String body, TemplateCategory category) {
        WhatsAppTemplateEntity entity = repo.findByTenantIdAndId(tenant.value(), id.value())
                .orElseThrow(() -> AdminApiException.notFound("template_not_found",
                        "no such template " + id.value()));
        TemplateStatus current = TemplateStatus.valueOf(entity.getStatus());
        if (current == TemplateStatus.APPROVED || current == TemplateStatus.SUBMITTED) {
            throw AdminApiException.conflict("not_editable",
                    "approved or submitted templates are immutable; clone with a new name to revise");
        }
        validateBody(body);
        entity.setBody(body);
        entity.setCategory(category.name());
        entity.setPlaceholderCount(countPlaceholders(body));
        if (current == TemplateStatus.REJECTED) {
            entity.setStatus(TemplateStatus.DRAFT.name());
            entity.setRejectionReason(null);
        }
        return toDomain(repo.save(entity));
    }

    public WhatsAppTemplate submit(Ids.TenantId tenant, TemplateId id) {
        WhatsAppTemplate template = requireForTenant(tenant, id);
        if (!template.status().canSubmit()) {
            throw AdminApiException.conflict("not_submittable",
                    "template in status " + template.status() + " cannot be submitted");
        }
        // Twilio Content API submit is the W6-follow-on integration. We
        // mark SUBMITTED + clear rejection_reason; the sync sweep will
        // promote to APPROVED once the platform wires the BSP call.
        WhatsAppTemplateEntity entity = repo.findById(id.value()).orElseThrow();
        entity.setStatus(TemplateStatus.SUBMITTED.name());
        entity.setSubmittedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return toDomain(repo.save(entity));
    }

    @Transactional
    public void delete(Ids.TenantId tenant, TemplateId id) {
        WhatsAppTemplateEntity entity = repo.findByTenantIdAndId(tenant.value(), id.value())
                .orElseThrow(() -> AdminApiException.notFound("template_not_found",
                        "no such template " + id.value()));
        TemplateStatus status = TemplateStatus.valueOf(entity.getStatus());
        if (status == TemplateStatus.APPROVED) {
            // Meta doesn't support template "deletion" once approved —
            // operators pause instead.
            entity.setStatus(TemplateStatus.PAUSED.name());
            repo.save(entity);
            return;
        }
        repo.delete(entity);
    }

    /** Lookup used by the {@code send_whatsapp_template} tool dispatcher
     *  in W6+. */
    public Optional<WhatsAppTemplate> findApproved(Ids.TenantId tenant, SenderId sender,
                                                   String name, String locale) {
        return repo.findByTenantIdAndSenderIdAndNameAndLocale(
                tenant.value(), sender.value(), name, locale)
                .map(this::toDomain)
                .filter(t -> t.status() == TemplateStatus.APPROVED);
    }

    /* ---------------------------- helpers --------------------------------- */

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

    private WhatsAppTemplate toDomain(WhatsAppTemplateEntity entity) {
        return new WhatsAppTemplate(
                new TemplateId(entity.getId()),
                new Ids.TenantId(entity.getTenantId()),
                new SenderId(entity.getSenderId()),
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
                toInstant(entity.getLastSyncAt())
        );
    }

    private static Instant toInstant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }
}
