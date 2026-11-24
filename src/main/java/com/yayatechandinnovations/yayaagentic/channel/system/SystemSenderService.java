package com.yayatechandinnovations.yayaagentic.channel.system;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundSenderService;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground.PlaygroundSenders;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointRepository;
import com.yayatechandinnovations.yayaagentic.persistence.SystemSenderEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SystemSenderRepository;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Integration-sender CRUD + domain⇄entity mapping ({@code
 * system-messaging-design.md} §3), sibling to {@code ChatEntryPointService}. The
 * sender's trust and addressing policies are stored as JSON; the delivery target
 * ({@code entryPoint}) must be an existing chat entry point in the same tenant.
 */
@Service
public class SystemSenderService {

    private final SystemSenderRepository repo;
    private final ChatEntryPointRepository entryPoints;
    private final ObjectMapper json;
    private final WhatsAppSenderRepository whatsappSenders;
    // Optional so the hand-built tests keep compiling; it provisions the
    // tenant's Playground simulator row a link to it needs (FK).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PlaygroundSenderService playgroundSenders;

    void setPlaygroundSenders(PlaygroundSenderService playgroundSenders) {
        this.playgroundSenders = playgroundSenders;
    }

    // Optional for the same reason: a platform sender links only for a
    // workspace it is assigned to (whatsapp-shared-sender-design.md §9.1).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderAssignmentRepository assignments;

    void setAssignments(com.yayatechandinnovations.yayaagentic.persistence.WhatsAppSenderAssignmentRepository assignments) {
        this.assignments = assignments;
    }

    public SystemSenderService(SystemSenderRepository repo,
                               ChatEntryPointRepository entryPoints,
                               ObjectMapper json,
                               WhatsAppSenderRepository whatsappSenders) {
        this.repo = repo;
        this.entryPoints = entryPoints;
        this.json = json;
        this.whatsappSenders = whatsappSenders;
    }

    /* -------------------------------- read -------------------------------- */

    public List<SystemSender> listForTenant(TenantId tenant) {
        return repo.findByTenantIdOrderByCreatedAtDesc(tenant.value()).stream()
                .map(this::toDomain).toList();
    }

    public Optional<SystemSender> find(TenantId tenant, Ids.SystemSenderId id) {
        return repo.findByTenantIdAndId(tenant.value(), id.value()).map(this::toDomain);
    }

    public SystemSender requireForTenant(TenantId tenant, Ids.SystemSenderId id) {
        return find(tenant, id).orElseThrow(() -> AdminApiException.notFound(
                "system_sender_not_found",
                "no such system sender '" + id.value() + "' in tenant '" + tenant.value() + "'"));
    }

    /* ------------------------------- create ------------------------------- */

    @Transactional
    public SystemSender create(TenantId tenant, Command cmd) {
        validate(tenant, cmd);
        String id = "sys_" + UUID.randomUUID().toString().replace("-", "");
        SystemSenderEntity e = new SystemSenderEntity(
                id, tenant.value(), cmd.entryPoint().trim(), cmd.displayName().trim());
        applyCommand(e, cmd);
        e.setUpdatedAt(now());
        return toDomain(repo.save(e));
    }

    /* ------------------------------- update ------------------------------- */

    @Transactional
    public SystemSender update(TenantId tenant, Ids.SystemSenderId id, Command cmd) {
        validate(tenant, cmd);
        SystemSenderEntity e = repo.findByTenantIdAndId(tenant.value(), id.value())
                .orElseThrow(() -> AdminApiException.notFound("system_sender_not_found",
                        "no such system sender '" + id.value() + "'"));
        e.setEntryPointId(cmd.entryPoint().trim());
        e.setDisplayName(cmd.displayName().trim());
        applyCommand(e, cmd);
        e.setUpdatedAt(now());
        return toDomain(repo.save(e));
    }

    @Transactional
    public void delete(TenantId tenant, Ids.SystemSenderId id) {
        SystemSenderEntity e = repo.findByTenantIdAndId(tenant.value(), id.value())
                .orElseThrow(() -> AdminApiException.notFound("system_sender_not_found",
                        "no such system sender '" + id.value() + "'"));
        repo.delete(e);
    }

    /* ------------------------------ helpers ------------------------------- */

    private void applyCommand(SystemSenderEntity e, Command cmd) {
        e.setEnabled(cmd.enabled());
        e.setTrustJson(writeJson(cmd.trust() == null ? SenderTrust.none() : cmd.trust()));
        e.setMediation((cmd.mediation() == null ? MediationMode.VERBATIM : cmd.mediation()).name());
        e.setAddressingJson(writeJson(
                cmd.addressing() == null ? AddressingPolicy.principalsOnly() : cmd.addressing()));
        e.setResponseToolId(cmd.responseToolId() == null || cmd.responseToolId().isBlank()
                ? null : cmd.responseToolId().trim());
        e.setCapabilityMaskJson(cmd.capabilityMask() == null ? null : writeJson(cmd.capabilityMask()));
        e.setLimitsJson(cmd.limits() == null ? null : writeJson(cmd.limits()));
        // Absent keeps the link (a provisioning re-run that predates WhatsApp
        // must not unlink it); blank unlinks; a value must be this tenant's sender.
        // The tenant's own Playground simulator counts: its messages land in the
        // Playground conversation of the recipient phone (WhatsAppOutreachLeg). Its
        // row is otherwise created by the first Playground message, so linking
        // creates it. Another tenant's simulator is not this tenant's sender.
        if (cmd.whatsappSenderId() != null) {
            String wa = cmd.whatsappSenderId().trim();
            if (wa.isEmpty()) {
                e.setWhatsappSenderId(null);
            } else {
                TenantId tenant = new TenantId(e.getTenantId());
                boolean ownSimulator = PlaygroundSenders.forTenant(tenant).value().equals(wa);
                if (ownSimulator) {
                    if (playgroundSenders != null) playgroundSenders.ensureForTenant(tenant);
                } else {
                    boolean own = whatsappSenders.findByTenantIdAndId(e.getTenantId(), wa).isPresent();
                    boolean assigned = !own && assignments != null
                            && assignments.findLive(wa, e.getTenantId()).isPresent();
                    if (!own && !assigned) {
                        throw AdminApiException.badRequest("unknown_whatsapp_sender",
                                "no WhatsApp sender '" + wa + "' in tenant '" + e.getTenantId() + "'");
                    }
                }
                e.setWhatsappSenderId(wa);
            }
        }
    }

    private void validate(TenantId tenant, Command cmd) {
        if (cmd.entryPoint() == null || cmd.entryPoint().isBlank()) {
            throw AdminApiException.badRequest("missing_entry_point", "entryPoint is required");
        }
        if (cmd.displayName() == null || cmd.displayName().isBlank()) {
            throw AdminApiException.badRequest("missing_display_name", "displayName is required");
        }
        // The delivery target must be a real chat entry point in this tenant —
        // "the chat the user added" (§6). Fail loudly rather than at ingest time.
        entryPoints.findByTenantIdAndId(tenant.value(), cmd.entryPoint().trim())
                .orElseThrow(() -> AdminApiException.badRequest("unknown_entry_point",
                        "no chat entry point '" + cmd.entryPoint() + "' in tenant '" + tenant.value() + "'"));
    }

    private SystemSender toDomain(SystemSenderEntity e) {
        return new SystemSender(
                new Ids.SystemSenderId(e.getId()),
                new TenantId(e.getTenantId()),
                e.getEntryPointId(),
                e.getDisplayName(),
                e.isEnabled(),
                readJson(e.getTrustJson(), SenderTrust.class, SenderTrust.none()),
                MediationMode.valueOf(e.getMediation()),
                Optional.ofNullable(e.getResponseToolId())
                        .filter(s -> !s.isBlank()).map(Ids.ToolId::new),
                readJson(e.getAddressingJson(), AddressingPolicy.class, AddressingPolicy.principalsOnly()),
                Optional.ofNullable(readJson(e.getCapabilityMaskJson(), CapabilityMask.class, null)),
                Optional.ofNullable(readJson(e.getLimitsJson(), RateAndQuiet.class, null)),
                e.getCreatedAt() == null ? Instant.now() : e.getCreatedAt().toInstant(),
                e.getUpdatedAt() == null ? Instant.now() : e.getUpdatedAt().toInstant(),
                Optional.ofNullable(e.getWhatsappSenderId()).filter(s -> !s.isBlank()));
    }

    private String writeJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize system-sender config", ex);
        }
    }

    private <T> T readJson(String raw, Class<T> type, T fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return json.readValue(raw, type);
        } catch (JsonProcessingException ex) {
            return fallback;
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Create/update payload; the controller maps its DTO to this. */
    public record Command(
            String entryPoint,
            String displayName,
            boolean enabled,
            SenderTrust trust,
            MediationMode mediation,
            AddressingPolicy addressing,
            String responseToolId,
            CapabilityMask capabilityMask,
            RateAndQuiet limits,
            String whatsappSenderId
    ) {
        public Command(String entryPoint, String displayName, boolean enabled, SenderTrust trust,
                       MediationMode mediation, AddressingPolicy addressing, String responseToolId,
                       CapabilityMask capabilityMask, RateAndQuiet limits) {
            this(entryPoint, displayName, enabled, trust, mediation, addressing, responseToolId,
                    capabilityMask, limits, null);
        }
    }
}
