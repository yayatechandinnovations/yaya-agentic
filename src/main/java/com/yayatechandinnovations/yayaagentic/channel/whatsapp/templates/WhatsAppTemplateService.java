package com.yayatechandinnovations.yayaagentic.channel.whatsapp.templates;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.channel.ProviderCredsResolver;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio.TwilioMessagingChannelProvider;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppTemplateEntity;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template CRUD + lifecycle arbiter. Operators (or an integration's
 * provisioning, through {@link #declare}) own the content; the platform talks
 * to Twilio's Content API behind the scenes: {@link #submit} creates the
 * Content resource and requests WhatsApp approval, {@link #syncSender} and the
 * scheduled sweep pull Meta's verdict, and an APPROVED row's ContentSid is what
 * the WhatsApp outreach leg sends (whatsapp-ops-integration-design.md §6.3).
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

    private final WhatsAppTemplateRepository repo;
    private final WhatsAppSenderService senders;
    private final TwilioMessagingChannelProvider twilio;
    private final ProviderCredsResolver credsResolver;
    private final ObjectMapper json;
    private final String publicBaseUrl;

    public WhatsAppTemplateService(WhatsAppTemplateRepository repo,
                                   WhatsAppSenderService senders,
                                   TwilioMessagingChannelProvider twilio,
                                   ProviderCredsResolver credsResolver,
                                   ObjectMapper json,
                                   @Value("${yaya.agentic.channels.twilio.webhook-base-url:${yaya.agentic.public-base-url:}}")
                                   String publicBaseUrl) {
        this.repo = repo;
        this.senders = senders;
        this.twilio = twilio;
        this.credsResolver = credsResolver;
        this.json = json;
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
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

    /**
     * Create or converge a template from a declaration (an integration's
     * catalog, §14.5). Identical content is a no-op whatever the status;
     * changed content on a SUBMITTED/APPROVED template is refused — Meta
     * templates are immutable, so the declarer bumps the name.
     */
    @Transactional
    public WhatsAppTemplate declare(Ids.TenantId tenant, SenderId sender, Declaration d) {
        WhatsAppSender s = senders.requireForTenant(tenant, sender);
        validateDeclaration(d);
        String hash = contentHash(d);
        Optional<WhatsAppTemplateEntity> existing = repo.findByTenantIdAndSenderIdAndNameAndLocale(
                tenant.value(), sender.value(), d.name(), d.language());
        if (existing.isPresent()) {
            WhatsAppTemplateEntity e = existing.get();
            if (hash.equals(e.getContentHash())) return toDomain(e);
            TemplateStatus current = TemplateStatus.valueOf(e.getStatus());
            if (current == TemplateStatus.APPROVED || current == TemplateStatus.SUBMITTED
                    || current == TemplateStatus.PAUSED) {
                throw AdminApiException.conflict("not_editable",
                        "template '" + d.name() + "' is " + current
                                + " with different content; declare it under a new name (e.g. _v2)");
            }
            applyDeclaration(e, d, hash);
            e.setStatus(TemplateStatus.DRAFT.name());
            e.setRejectionReason(null);
            e.setBspContentSid(null);
            return toDomain(repo.save(e));
        }
        WhatsAppTemplateEntity e = new WhatsAppTemplateEntity(s.tenant().value(), s.id().value(),
                d.name(), d.language(), d.category().name(), d.body(), countPlaceholders(d.body()));
        applyDeclaration(e, d, hash);
        return toDomain(repo.save(e));
    }

    /**
     * Register the content with Twilio (Content API) and request WhatsApp
     * approval. Meta's verdict arrives through {@link #syncSender} / the sweep.
     */
    @Transactional
    public WhatsAppTemplate submit(Ids.TenantId tenant, TemplateId id) {
        WhatsAppTemplate template = requireForTenant(tenant, id);
        if (!template.status().canSubmit()) {
            throw AdminApiException.conflict("not_submittable",
                    "template in status " + template.status() + " cannot be submitted");
        }
        if (!credsResolver.isReady()) {
            throw AdminApiException.conflict("provider_not_configured",
                    "Twilio is not configured on this platform instance");
        }
        WhatsAppTemplateEntity entity = repo.findById(id.value()).orElseThrow();
        WhatsAppSender sender = senders.requireForTenant(tenant, template.sender());
        var creds = credsResolver.resolve(sender);
        try {
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
        } catch (RuntimeException e) {
            log.warn("Template {} submit to Twilio failed: {}", entity.getName(), e.getMessage());
            throw AdminApiException.conflict("submit_failed",
                    "Twilio rejected the template: " + e.getMessage());
        }
        entity.setSubmittedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return toDomain(repo.save(entity));
    }

    /** Pull the approval state of every submitted template of one sender. */
    @Transactional
    public List<WhatsAppTemplate> syncSender(Ids.TenantId tenant, SenderId sender) {
        WhatsAppSender s = senders.requireForTenant(tenant, sender);
        List<WhatsAppTemplateEntity> rows = repo.findByTenantIdAndSenderIdOrderByNameAscLocaleAsc(
                tenant.value(), sender.value());
        if (credsResolver.isReady()) {
            var creds = credsResolver.resolve(s);
            for (WhatsAppTemplateEntity e : rows) syncOne(e, creds);
        }
        return rows.stream().map(this::toDomain).toList();
    }

    /** Meta reviews in minutes to a day; poll what is still waiting (and catch pauses). */
    @Scheduled(fixedDelayString = "${yaya.agentic.channels.whatsapp.template-sync-ms:600000}",
            initialDelayString = "${yaya.agentic.channels.whatsapp.template-sync-initial-ms:120000}")
    @Transactional
    public void syncPending() {
        if (!credsResolver.isReady()) return;
        for (WhatsAppTemplateEntity e : repo.findByStatusIn(List.of(
                TemplateStatus.SUBMITTED.name(), TemplateStatus.APPROVED.name()))) {
            try {
                WhatsAppSender s = senders.requireForTenant(new Ids.TenantId(e.getTenantId()),
                        new SenderId(e.getSenderId()));
                syncOne(e, credsResolver.resolve(s));
            } catch (RuntimeException ex) {
                log.debug("template sync skipped for {}: {}", e.getName(), ex.getMessage());
            }
        }
    }

    private void syncOne(WhatsAppTemplateEntity e, com.yayatechandinnovations.yayaagentic.channel.ProviderCreds creds) {
        if (e.getBspContentSid() == null || e.getBspContentSid().isBlank()) return;
        try {
            applyApproval(e, twilio.fetchWhatsAppApproval(e.getBspContentSid(), creds));
            e.setLastSyncAt(OffsetDateTime.now(ZoneOffset.UTC));
            repo.save(e);
        } catch (RuntimeException ex) {
            log.warn("Template {} approval sync failed: {}", e.getName(), ex.getMessage());
        }
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
        String canonical = String.join("\u0001", d.language(), d.category().name(), d.header(), d.body(),
                String.join("\u0002", d.buttons()), String.join("\u0002", d.bodySamples()),
                d.mediaFilename() == null ? "" : d.mediaFilename());
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
                toInstant(entity.getLastSyncAt()),
                entity.getHeaderKind(),
                readList(entity.getButtonsJson())
        );
    }

    private static Instant toInstant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }
}
