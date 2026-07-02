package com.yayatechandinnovations.yayaagentic.channel.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.channel.ChannelEndpointRef;
import com.yayatechandinnovations.yayaagentic.channel.binding.ChannelBindingService;
import com.yayatechandinnovations.yayaagentic.channel.binding.OnOverrideDenied;
import com.yayatechandinnovations.yayaagentic.channel.binding.OnUnresolvedPolicy;
import com.yayatechandinnovations.yayaagentic.channel.binding.RoutingRule;
import com.yayatechandinnovations.yayaagentic.channel.binding.SessionPolicy;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Chat entry point CRUD + lifecycle arbiter ({@code chat-channel-design.md} §6),
 * sibling to {@code SmsSenderService}. Creating an entry point is transactional:
 * it inserts the {@code chat_entry_points} row <em>and</em> its {@code MatchAny}
 * channel binding together, so {@code ChannelProfileResolver} resolves it the
 * instant it goes {@code ACTIVE}.
 */
@Service
public class ChatEntryPointService {

    private static final Logger log = LoggerFactory.getLogger(ChatEntryPointService.class);
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$");
    private static final Base64.Encoder URL64 = Base64.getUrlEncoder().withoutPadding();

    private final ChatEntryPointRepository repo;
    private final ChannelBindingService bindings;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public ChatEntryPointService(ChatEntryPointRepository repo,
                                 ChannelBindingService bindings,
                                 ObjectMapper json) {
        this.repo = repo;
        this.bindings = bindings;
        this.json = json;
    }

    /* ------------------------------- read -------------------------------- */

    public List<ChatEntryPoint> listForTenant(TenantId tenant) {
        return repo.findByTenantIdOrderByCreatedAtDesc(tenant.value()).stream().map(this::toDomain).toList();
    }

    public ChatEntryPoint requireForTenant(TenantId tenant, ChatEntryPointId id) {
        return toDomain(loadEntity(tenant, id));
    }

    /** Public front-door resolution by opaque handle. Empty for unknown handles —
     *  the caller must not distinguish unknown from deactivated (§6.1). */
    public Optional<ChatEntryPoint> findByHandle(String handle) {
        if (handle == null || handle.isBlank()) return Optional.empty();
        return repo.findByHandle(handle).map(this::toDomain);
    }

    /* ------------------------------ create ------------------------------- */

    @Transactional
    public ChatEntryPoint createDraft(TenantId tenant, CreateCommand cmd) {
        validate(cmd);

        Ids.ProfileId profile = new Ids.ProfileId(cmd.profileId(),
                cmd.profileVersion() == null ? 1 : cmd.profileVersion());

        String id = "chat_" + compactUuid();
        ChatEntryPointEntity e = new ChatEntryPointEntity(
                id, tenant.value(), mintHandle(), profile.value(),
                cmd.profileVersion(), cmd.displayName().trim(), cmd.logoUrl().trim(),
                writeJson(SessionPolicy.defaults()), EntryPointStatus.DRAFT.name());
        e.setAccentColorHex(cmd.accentColorHex());
        e.setWelcomeText(cmd.welcomeText());
        e.setDefaultLocale(cmd.defaultLocale());
        e.setTagline(cmd.tagline());
        e.setDiscoverable(Boolean.TRUE.equals(cmd.discoverable()));
        AuthMode authMode = cmd.authMode() == null ? AuthMode.ANONYMOUS_ONLY : cmd.authMode();
        e.setAuthMode(authMode.name());
        // yaya-agentic is the source of truth for the sender key: a fresh entry
        // point mints its own code, which the operator registers on the tenant's
        // resolver side. We never accept an inbound key on create.
        AuthConfig authConfig = withMintedSenderKey(
                cmd.authConfig() == null ? new AuthConfig.None() : cmd.authConfig(), null);
        e.setAuthConfigJson(writeJson(authConfig));
        e.setAbusePolicyJson(writeJson(cmd.abuse() == null ? AbusePolicy.defaults() : cmd.abuse()));
        // A resolver-exchange entry point authenticates from the verified device
        // phone, so a verified phone is structurally required — force it rather
        // than discover the gap at session start.
        boolean resolverExchange = cmd.authConfig() instanceof AuthConfig.ResolverExchange;
        e.setRequiresVerifiedPhone(resolverExchange || Boolean.TRUE.equals(cmd.requiresVerifiedPhone()));
        e.setAllowedOverrideProfilesJson(writeJson(sortedList(cmd.allowedOverrideProfiles())));
        if (cmd.onOverrideDenied() != null) e.setOnOverrideDenied(cmd.onOverrideDenied().name());
        e.setUpdatedAt(now());

        ChatEntryPointEntity saved = repo.save(e);

        // The MatchAny binding pins the entry point to its profile. authResolverId
        // flows from a ResolverExchange auth config; onUnresolved follows the auth mode.
        Long authResolverId = (cmd.authConfig() instanceof AuthConfig.ResolverExchange rx) ? rx.authResolverId() : null;
        bindings.create(tenant,
                new ChannelEndpointRef.ChatEntryPointRef(new ChatEntryPointId(saved.getId())),
                profile,
                Optional.ofNullable(cmd.profileVersion()),
                new RoutingRule.MatchAny(),
                SessionPolicy.defaults(),
                100,
                authResolverId,
                authMode.requiresTokenToStart() ? OnUnresolvedPolicy.REJECT : OnUnresolvedPolicy.ANONYMOUS,
                // Override guardrails for chat live on the entry point row (read by
                // ChatAuthService), not this MatchAny binding — leave it at defaults.
                Set.of(), OnOverrideDenied.IGNORE);

        return toDomain(saved);
    }

    /* ------------------------------ update ------------------------------- */

    @Transactional
    public ChatEntryPoint updateBranding(TenantId tenant, ChatEntryPointId id, UpdateCommand cmd) {
        ChatEntryPointEntity e = loadEntity(tenant, id);
        if (cmd.displayName() != null && !cmd.displayName().isBlank()) e.setDisplayName(cmd.displayName().trim());
        if (cmd.logoUrl() != null && !cmd.logoUrl().isBlank()) e.setLogoUrl(cmd.logoUrl().trim());
        if (cmd.accentColorHex() != null) {
            validateColor(cmd.accentColorHex());
            e.setAccentColorHex(cmd.accentColorHex());
        }
        if (cmd.welcomeText() != null) e.setWelcomeText(cmd.welcomeText());
        if (cmd.defaultLocale() != null && !cmd.defaultLocale().isBlank()) e.setDefaultLocale(cmd.defaultLocale());
        if (cmd.tagline() != null) e.setTagline(cmd.tagline());
        if (cmd.discoverable() != null) e.setDiscoverable(cmd.discoverable());
        if (cmd.authMode() != null) e.setAuthMode(cmd.authMode().name());
        if (cmd.authConfig() != null) {
            // The sender key is immutable once minted — carry the stored code
            // forward so an operator's branding edit can't rotate it out from
            // under the resolver. A fresh switch to resolver-exchange mints one.
            AuthConfig current = readJson(e.getAuthConfigJson(), AuthConfig.class);
            e.setAuthConfigJson(writeJson(withMintedSenderKey(cmd.authConfig(), current)));
        }
        if (cmd.abuse() != null) e.setAbusePolicyJson(writeJson(cmd.abuse()));
        if (cmd.requiresVerifiedPhone() != null) e.setRequiresVerifiedPhone(cmd.requiresVerifiedPhone());
        if (cmd.allowedOverrideProfiles() != null) {
            e.setAllowedOverrideProfilesJson(writeJson(sortedList(cmd.allowedOverrideProfiles())));
        }
        if (cmd.onOverrideDenied() != null) e.setOnOverrideDenied(cmd.onOverrideDenied().name());
        // Switching to resolver-exchange forces a verified phone (see createDraft).
        if (cmd.authConfig() instanceof AuthConfig.ResolverExchange) e.setRequiresVerifiedPhone(true);
        e.setUpdatedAt(now());
        return toDomain(repo.save(e));
    }

    /* ---------------------------- lifecycle ------------------------------ */

    @Transactional
    public ChatEntryPoint publish(TenantId tenant, ChatEntryPointId id) {
        return transition(tenant, id, EntryPointStatus.ACTIVE);
    }

    @Transactional
    public ChatEntryPoint pause(TenantId tenant, ChatEntryPointId id) {
        return transition(tenant, id, EntryPointStatus.PAUSED);
    }

    @Transactional
    public ChatEntryPoint resume(TenantId tenant, ChatEntryPointId id) {
        return transition(tenant, id, EntryPointStatus.ACTIVE);
    }

    @Transactional
    public ChatEntryPoint deactivate(TenantId tenant, ChatEntryPointId id) {
        return transition(tenant, id, EntryPointStatus.DEACTIVATED);
    }

    @Transactional
    public ChatEntryPoint rotateHandle(TenantId tenant, ChatEntryPointId id) {
        ChatEntryPointEntity e = loadEntity(tenant, id);
        e.setHandle(mintHandle());
        e.setUpdatedAt(now());
        return toDomain(repo.save(e));
    }

    /* ------------------------------ helpers ------------------------------ */

    private ChatEntryPoint transition(TenantId tenant, ChatEntryPointId id, EntryPointStatus to) {
        ChatEntryPointEntity e = loadEntity(tenant, id);
        EntryPointStatus from = EntryPointStatus.parse(e.getStatus());
        if (from == to) return toDomain(e);
        if (!from.canTransitionTo(to)) {
            throw AdminApiException.conflict("illegal_transition",
                    "chat entry point in status " + from + " cannot transition to " + to);
        }
        e.setStatus(to.name());
        e.setUpdatedAt(now());
        return toDomain(repo.save(e));
    }

    private void validate(CreateCommand cmd) {
        if (cmd == null) throw AdminApiException.badRequest("missing_body", "request body required");
        if (cmd.profileId() == null || cmd.profileId().isBlank()) {
            throw AdminApiException.badRequest("missing_profile", "profileId required");
        }
        if (cmd.displayName() == null || cmd.displayName().isBlank()) {
            throw AdminApiException.badRequest("missing_name", "displayName required");
        }
        if (cmd.logoUrl() == null || cmd.logoUrl().isBlank()) {
            throw AdminApiException.badRequest("missing_logo", "logoUrl required");
        }
        if (cmd.accentColorHex() != null) validateColor(cmd.accentColorHex());
    }

    private void validateColor(String hex) {
        if (!hex.isBlank() && !HEX_COLOR.matcher(hex).matches()) {
            throw AdminApiException.badRequest("bad_color", "accentColorHex must be #RRGGBB or #RRGGBBAA");
        }
    }

    private ChatEntryPointEntity loadEntity(TenantId tenant, ChatEntryPointId id) {
        return repo.findByTenantIdAndId(tenant.value(), id.value())
                .orElseThrow(() -> AdminApiException.notFound("entry_point_not_found",
                        "no such chat entry point '" + id.value() + "' in tenant '" + tenant.value() + "'"));
    }

    /** 18 random bytes → 24 url-safe chars. High entropy so the handle space is
     *  not enumerable (§6.1). */
    private String mintHandle() {
        byte[] raw = new byte[18];
        random.nextBytes(raw);
        return "e_" + URL64.encodeToString(raw);
    }

    /** Unambiguous uppercase alphanumerics — no I/O/0/1 — so an operator can
     *  read the code off the console and type it into the tenant's resolver
     *  config without transcription errors. */
    private static final char[] SENDER_KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    /** Ensures a {@link AuthConfig.ResolverExchange} carries a stable 8-char
     *  code (e.g. {@code "F0DX-12FF"}). Reuses the previously stored key when the
     *  config already had one; otherwise mints a fresh one. Non-resolver configs
     *  pass through untouched. */
    private AuthConfig withMintedSenderKey(AuthConfig incoming, AuthConfig previous) {
        if (!(incoming instanceof AuthConfig.ResolverExchange rx)) return incoming;
        String existing = previous instanceof AuthConfig.ResolverExchange p ? p.senderKey() : null;
        String key = (existing != null && !existing.isBlank()) ? existing : mintSenderKey();
        return new AuthConfig.ResolverExchange(rx.authResolverId(), key);
    }

    private String mintSenderKey() {
        char[] out = new char[9];
        for (int i = 0, p = 0; i < 8; i++, p++) {
            if (i == 4) out[p++] = '-';
            out[p] = SENDER_KEY_ALPHABET[random.nextInt(SENDER_KEY_ALPHABET.length)];
        }
        return new String(out);
    }

    ChatEntryPoint toDomain(ChatEntryPointEntity e) {
        return new ChatEntryPoint(
                new ChatEntryPointId(e.getId()),
                new TenantId(e.getTenantId()),
                e.getHandle(),
                new Ids.ProfileId(e.getProfileId(), e.getProfileVersion() == null ? 1 : e.getProfileVersion()),
                new Branding(e.getDisplayName(), e.getLogoUrl(), e.getAccentColorHex(),
                        e.getWelcomeText(), e.getDefaultLocale(), e.getTagline()),
                AuthMode.parse(e.getAuthMode()),
                readJson(e.getAuthConfigJson(), AuthConfig.class),
                readJson(e.getSessionPolicyJson(), SessionPolicy.class),
                readJson(e.getAbusePolicyJson(), AbusePolicy.class),
                e.isRequiresVerifiedPhone(),
                e.isDiscoverable(),
                EntryPointStatus.parse(e.getStatus()),
                toInstant(e.getCreatedAt()),
                toInstant(e.getUpdatedAt()),
                readStringSet(e.getAllowedOverrideProfilesJson()),
                OnOverrideDenied.parse(e.getOnOverrideDenied()));
    }

    private Set<String> readStringSet(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) return Set.of();
        try {
            List<String> list = json.readValue(jsonArray, new TypeReference<List<String>>() {});
            return list == null ? Set.of() : Set.copyOf(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("decode allowed_override_profiles_json", e);
        }
    }

    private static List<String> sortedList(Set<String> keys) {
        return keys == null ? List.of() : keys.stream().filter(k -> k != null && !k.isBlank())
                .map(String::trim).distinct().sorted().toList();
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

    /** Create command — keeps the service signature stable as entry points grow. */
    public record CreateCommand(
            String profileId,
            Integer profileVersion,
            String displayName,
            String logoUrl,
            String accentColorHex,
            String welcomeText,
            String defaultLocale,
            String tagline,
            Boolean discoverable,
            AuthMode authMode,
            AuthConfig authConfig,
            AbusePolicy abuse,
            Boolean requiresVerifiedPhone,
            Set<String> allowedOverrideProfiles,
            OnOverrideDenied onOverrideDenied) {}

    /** Partial update — null fields are left unchanged. */
    public record UpdateCommand(
            String displayName,
            String logoUrl,
            String accentColorHex,
            String welcomeText,
            String defaultLocale,
            String tagline,
            Boolean discoverable,
            AuthMode authMode,
            AuthConfig authConfig,
            AbusePolicy abuse,
            Boolean requiresVerifiedPhone,
            Set<String> allowedOverrideProfiles,
            OnOverrideDenied onOverrideDenied) {}
}
