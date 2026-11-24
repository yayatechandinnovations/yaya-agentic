package com.yayatechandinnovations.yayaagentic.channel.binding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.channel.ChannelEndpointRef;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatEntryPointId;
import com.yayatechandinnovations.yayaagentic.channel.sms.SmsSenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSender;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelBindingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelBindingRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ChatEntryPointRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ProfileEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * CRUD + hot-path resolution for {@link ChannelBinding}. The
 * {@code ChannelProfileResolver} delegates to {@link #findFor} on every
 * inbound message — must stay cheap. The admin write paths validate cross-
 * tenant references (a binding's endpoint MUST belong to the binding's
 * tenant; see §16 cross-tenant binding mistake risk).
 */
@Service
public class ChannelBindingService {

    private final ChannelBindingRepository repo;
    private final ProfileRepository profiles;
    private final WhatsAppSenderService senders;
    private final com.yayatechandinnovations.yayaagentic.channel.sms.SmsSenderService smsSenders;
    // Repository, not ChatEntryPointService — that service depends on this one to
    // auto-create its MatchAny binding, so injecting the service would cycle.
    private final ChatEntryPointRepository chatEntryPoints;
    private final ObjectMapper json;

    public ChannelBindingService(ChannelBindingRepository repo,
                                 ProfileRepository profiles,
                                 WhatsAppSenderService senders,
                                 com.yayatechandinnovations.yayaagentic.channel.sms.SmsSenderService smsSenders,
                                 ChatEntryPointRepository chatEntryPoints,
                                 ObjectMapper json) {
        this.repo = repo;
        this.profiles = profiles;
        this.senders = senders;
        this.smsSenders = smsSenders;
        this.chatEntryPoints = chatEntryPoints;
        this.json = json;
    }

    /* ---------------------------- read ------------------------------------ */

    public List<ChannelBinding> listForTenant(Ids.TenantId tenant) {
        return repo.findByTenantIdOrderByPriorityAscIdAsc(tenant.value()).stream()
                .map(this::toDomain)
                .toList();
    }

    public ChannelBinding requireForTenant(Ids.TenantId tenant, BindingId id) {
        return repo.findByTenantIdAndId(tenant.value(), id.value())
                .map(this::toDomain)
                .orElseThrow(() -> AdminApiException.notFound("binding_not_found",
                        "no such binding " + id.value() + " in tenant '" + tenant.value() + "'"));
    }

    /**
     * Routing-agnostic lookup of the lowest-priority ACTIVE binding for an
     * endpoint. Used on the auth path — where the Principal isn't minted yet
     * so routing rules can't be evaluated. Returns empty when the endpoint
     * has no ACTIVE binding.
     */
    public Optional<ChannelBinding> findLowestPriorityActiveForEndpoint(
            Ids.TenantId tenant, ChannelEndpointRef endpoint) {
        return repo.findByTenantIdAndEndpointKindAndEndpointIdAndStatusOrderByPriorityAscIdAsc(
                        tenant.value(),
                        endpoint.kindName(),
                        endpoint.externalId(),
                        BindingStatus.ACTIVE.name())
                .stream()
                .findFirst()
                .map(this::toDomain);
    }

    public Optional<ChannelBinding> findFor(Ids.TenantId tenant,
                                            ChannelEndpointRef endpoint,
                                            Principal endUser,
                                            Map<String, Object> hints) {
        List<ChannelBindingEntity> rows = repo
                .findByTenantIdAndEndpointKindAndEndpointIdAndStatusOrderByPriorityAscIdAsc(
                        tenant.value(),
                        endpoint.kindName(),
                        endpoint.externalId(),
                        BindingStatus.ACTIVE.name());
        for (ChannelBindingEntity row : rows) {
            ChannelBinding binding = toDomain(row);
            if (matches(binding.routing(), endUser, hints)) {
                return Optional.of(binding);
            }
        }
        return Optional.empty();
    }

    /** Pure routing-rule evaluation — extracted for direct unit testing. */
    public boolean matches(RoutingRule rule, Principal endUser, Map<String, Object> hints) {
        return switch (rule) {
            case RoutingRule.MatchAny m -> true;
            case RoutingRule.KeywordPrefix kp -> matchesKeywordPrefix(kp, hints);
            case RoutingRule.Locale loc -> matchesLocale(loc, hints);
            case RoutingRule.EndUserClaim ec -> matchesClaim(ec, endUser);
        };
    }

    private boolean matchesKeywordPrefix(RoutingRule.KeywordPrefix rule,
                                         Map<String, Object> hints) {
        if (rule.keywords().isEmpty()) return false;
        String body = stringHint(hints, "channel.inbound.body");
        if (body == null || body.isBlank()) return false;
        String trimmed = body.trim();
        for (String keyword : rule.keywords()) {
            if (keyword == null || keyword.isEmpty()) continue;
            boolean ok = rule.caseInsensitive()
                    ? trimmed.toLowerCase(Locale.ROOT).startsWith(keyword.toLowerCase(Locale.ROOT))
                    : trimmed.startsWith(keyword);
            if (ok) return true;
        }
        return false;
    }

    private boolean matchesLocale(RoutingRule.Locale rule, Map<String, Object> hints) {
        String locale = stringHint(hints, "channel.locale");
        if (locale == null) return false;
        if (rule.locales().contains(locale)) return true;
        // Allow language-only matches against fully qualified locales —
        // "es" rule matches "es-DO" inbound.
        for (String allowed : rule.locales()) {
            if (allowed != null && locale.startsWith(allowed + "-")) return true;
        }
        return false;
    }

    private boolean matchesClaim(RoutingRule.EndUserClaim rule, Principal endUser) {
        if (endUser == null) return false;
        Object value = endUser.claims().get(rule.claim());
        return value != null && rule.equals().equals(value.toString());
    }

    private static String stringHint(Map<String, Object> hints, String key) {
        Object v = hints == null ? null : hints.get(key);
        return v == null ? null : v.toString();
    }

    /* ---------------------------- write ----------------------------------- */

    @Transactional
    public ChannelBinding create(Ids.TenantId tenant,
                                 ChannelEndpointRef endpoint,
                                 Ids.ProfileId profile,
                                 Optional<Integer> profileVersion,
                                 RoutingRule routing,
                                 SessionPolicy sessionPolicy,
                                 int priority,
                                 Long authResolverId,
                                 OnUnresolvedPolicy onUnresolved,
                                 Set<String> allowedOverrideProfiles,
                                 OnOverrideDenied onOverrideDenied) {
        return create(tenant, endpoint, profile, profileVersion, routing, sessionPolicy, priority,
                authResolverId, onUnresolved, allowedOverrideProfiles, onOverrideDenied, null);
    }

    @Transactional
    public ChannelBinding create(Ids.TenantId tenant,
                                 ChannelEndpointRef endpoint,
                                 Ids.ProfileId profile,
                                 Optional<Integer> profileVersion,
                                 RoutingRule routing,
                                 SessionPolicy sessionPolicy,
                                 int priority,
                                 Long authResolverId,
                                 OnUnresolvedPolicy onUnresolved,
                                 Set<String> allowedOverrideProfiles,
                                 OnOverrideDenied onOverrideDenied,
                                 String unresolvedReplyText) {
        validateEndpointBelongsToTenant(tenant, endpoint);
        validateProfileExists(tenant, profile, profileVersion);
        validateAutoReply(onUnresolved, unresolvedReplyText);
        validatePlatformSenderBinding(endpoint, authResolverId,
                onUnresolved == null ? OnUnresolvedPolicy.REJECT : onUnresolved);

        ChannelBindingEntity entity = new ChannelBindingEntity(
                tenant.value(),
                endpoint.kindName(),
                endpoint.externalId(),
                profile.value(),
                profileVersion.orElse(null),
                writeJson(routing),
                writeJson(sessionPolicy),
                priority,
                BindingStatus.ACTIVE.name());
        entity.setAuthResolverId(authResolverId);
        entity.setOnUnresolved((onUnresolved == null
                ? OnUnresolvedPolicy.REJECT : onUnresolved).name());
        entity.setAllowedOverrideProfilesJson(writeJson(sortedList(allowedOverrideProfiles)));
        entity.setOnOverrideDenied((onOverrideDenied == null
                ? OnOverrideDenied.IGNORE : onOverrideDenied).name());
        entity.setUnresolvedReplyText(unresolvedReplyText);
        return toDomain(repo.save(entity));
    }

    /** The reply text a binding may answer an unknown caller with: at most this long. */
    public static final int MAX_AUTO_REPLY_CHARS = 1000;

    /** AUTO_REPLY without words to say is a misconfiguration, caught at the admin edge. */
    static void validateAutoReply(OnUnresolvedPolicy onUnresolved, String text) {
        boolean blank = text == null || text.isBlank();
        if (onUnresolved == OnUnresolvedPolicy.AUTO_REPLY && blank) {
            throw AdminApiException.badRequest("bad_unresolved_reply",
                    "onUnresolved AUTO_REPLY needs unresolvedReplyText");
        }
        if (!blank && text.trim().length() > MAX_AUTO_REPLY_CHARS) {
            throw AdminApiException.badRequest("bad_unresolved_reply",
                    "unresolvedReplyText is at most " + MAX_AUTO_REPLY_CHARS + " characters");
        }
    }

    private static List<String> sortedList(Set<String> keys) {
        return keys == null ? List.of() : keys.stream().filter(k -> k != null && !k.isBlank())
                .map(String::trim).distinct().sorted().toList();
    }

    @Transactional
    public ChannelBinding update(Ids.TenantId tenant,
                                 BindingId id,
                                 Ids.ProfileId profile,
                                 Optional<Integer> profileVersion,
                                 RoutingRule routing,
                                 SessionPolicy sessionPolicy,
                                 int priority,
                                 BindingStatus status,
                                 Long authResolverId,
                                 OnUnresolvedPolicy onUnresolved,
                                 Set<String> allowedOverrideProfiles,
                                 OnOverrideDenied onOverrideDenied) {
        return update(tenant, id, profile, profileVersion, routing, sessionPolicy, priority, status,
                authResolverId, onUnresolved, allowedOverrideProfiles, onOverrideDenied, null);
    }

    /**
     * {@code unresolvedReplyText} null keeps the stored text (so an older
     * client's PUT does not wipe it); "" clears it.
     */
    @Transactional
    public ChannelBinding update(Ids.TenantId tenant,
                                 BindingId id,
                                 Ids.ProfileId profile,
                                 Optional<Integer> profileVersion,
                                 RoutingRule routing,
                                 SessionPolicy sessionPolicy,
                                 int priority,
                                 BindingStatus status,
                                 Long authResolverId,
                                 OnUnresolvedPolicy onUnresolved,
                                 Set<String> allowedOverrideProfiles,
                                 OnOverrideDenied onOverrideDenied,
                                 String unresolvedReplyText) {
        ChannelBindingEntity entity = repo.findByTenantIdAndId(tenant.value(), id.value())
                .orElseThrow(() -> AdminApiException.notFound("binding_not_found",
                        "no such binding " + id.value()));
        validateProfileExists(tenant, profile, profileVersion);
        if ("WHATSAPP_SENDER".equals(entity.getEndpointKind())) {
            validatePlatformSenderBinding(
                    new ChannelEndpointRef.WhatsAppSenderRef(new SenderId(entity.getEndpointId())),
                    authResolverId,
                    onUnresolved == null ? OnUnresolvedPolicy.parse(entity.getOnUnresolved()) : onUnresolved);
        }
        String effectiveText = unresolvedReplyText == null ? entity.getUnresolvedReplyText() : unresolvedReplyText;
        validateAutoReply(onUnresolved == null ? OnUnresolvedPolicy.parse(entity.getOnUnresolved()) : onUnresolved,
                effectiveText);

        entity.setProfileId(profile.value());
        entity.setProfileVersion(profileVersion.orElse(null));
        entity.setRoutingJson(writeJson(routing));
        entity.setSessionPolicyJson(writeJson(sessionPolicy));
        entity.setPriority(priority);
        entity.setAuthResolverId(authResolverId);
        if (onUnresolved != null) entity.setOnUnresolved(onUnresolved.name());
        entity.setAllowedOverrideProfilesJson(writeJson(sortedList(allowedOverrideProfiles)));
        if (onOverrideDenied != null) entity.setOnOverrideDenied(onOverrideDenied.name());
        if (unresolvedReplyText != null) entity.setUnresolvedReplyText(unresolvedReplyText);
        if (status == BindingStatus.DEACTIVATED
                && !entity.getStatus().equals(BindingStatus.DEACTIVATED.name())) {
            entity.setDeactivatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        entity.setStatus(status.name());
        return toDomain(repo.save(entity));
    }

    /**
     * Repoint an endpoint's active binding to a different profile, leaving every
     * other binding field untouched. Used when a chat entry point's bound
     * profile is edited — the entry point row mirrors the profile, but this
     * MatchAny binding is what {@code ChannelProfileResolver} actually resolves
     * against at session start, so both must move together.
     */
    @Transactional
    public void repointEndpointProfile(Ids.TenantId tenant,
                                       ChannelEndpointRef endpoint,
                                       Ids.ProfileId profile,
                                       Optional<Integer> profileVersion) {
        validateProfileExists(tenant, profile, profileVersion);
        ChannelBindingEntity row = repo
                .findByTenantIdAndEndpointKindAndEndpointIdAndStatusOrderByPriorityAscIdAsc(
                        tenant.value(), endpoint.kindName(), endpoint.externalId(),
                        BindingStatus.ACTIVE.name())
                .stream().findFirst()
                .orElseThrow(() -> AdminApiException.notFound("binding_not_found",
                        "no active binding for endpoint '" + endpoint.externalId()
                                + "' in tenant '" + tenant.value() + "'"));
        row.setProfileId(profile.value());
        row.setProfileVersion(profileVersion.orElse(null));
        repo.save(row);
    }

    @Transactional
    public void deactivate(Ids.TenantId tenant, BindingId id) {
        ChannelBindingEntity entity = repo.findByTenantIdAndId(tenant.value(), id.value())
                .orElseThrow(() -> AdminApiException.notFound("binding_not_found",
                        "no such binding " + id.value()));
        if (!BindingStatus.DEACTIVATED.name().equals(entity.getStatus())) {
            entity.setStatus(BindingStatus.DEACTIVATED.name());
            entity.setDeactivatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            repo.save(entity);
        }
    }

    /* ---------------------------- validation ------------------------------ */

    /**
     * whatsapp-shared-sender-design.md §7.5 — on a platform sender only
     * registered staff reach an assistant, so every binding there must resolve
     * the caller and refuse anyone the resolver doesn't know. An anonymous FAQ
     * binding would answer the public on a number many businesses share.
     */
    private void validatePlatformSenderBinding(ChannelEndpointRef endpoint, Long authResolverId,
                                               OnUnresolvedPolicy onUnresolved) {
        if (!(endpoint instanceof ChannelEndpointRef.WhatsAppSenderRef ref)) return;
        boolean platform = senders.findById(ref.id()).map(WhatsAppSender::isPlatform).orElse(false);
        if (platform && (authResolverId == null || onUnresolved != OnUnresolvedPolicy.REJECT)) {
            throw AdminApiException.badRequest("platform_sender_requires_resolver",
                    "a binding on a shared (platform) WhatsApp number needs an auth resolver and "
                            + "onUnresolved REJECT — only registered staff may talk to an assistant there");
        }
    }

    private void validateEndpointBelongsToTenant(Ids.TenantId tenant,
                                                 ChannelEndpointRef endpoint) {
        switch (endpoint) {
            case ChannelEndpointRef.WhatsAppSenderRef senderRef -> {
                // Throws sender_not_found if absent OR if cross-tenant — service
                // already filters by tenant, so cross-tenant lookups return
                // empty and surface as not_found. The §16-mitigated risk is
                // covered: there's no path to bind a tenant's profile to
                // another tenant's sender. A platform sender binds only for a
                // workspace it is assigned to.
                senders.requireUsable(tenant, senderRef.id());
            }
            case ChannelEndpointRef.SmsSenderRef smsRef ->
                    // same cross-tenant guarantee as WhatsApp — tenant-scoped lookup
                    smsSenders.requireForTenant(tenant, smsRef.id());
            case ChannelEndpointRef.ChatEntryPointRef chatRef -> {
                // tenant-scoped lookup via the repository (not the service — cycle).
                if (chatEntryPoints.findByTenantIdAndId(tenant.value(), chatRef.id().value()).isEmpty()) {
                    throw AdminApiException.notFound("entry_point_not_found",
                            "no such chat entry point '" + chatRef.id().value()
                                    + "' in tenant '" + tenant.value() + "'");
                }
            }
        }
    }

    private void validateProfileExists(Ids.TenantId tenant,
                                       Ids.ProfileId profile,
                                       Optional<Integer> profileVersion) {
        int version = profileVersion.orElseGet(() -> {
            // Pin to ACTIVE version when unspecified — same convention as
            // identity-mapping resolution.
            return profile.version();
        });
        Optional<ProfileEntity> row =
                profiles.findByTenantIdAndIdAndVersion(tenant.value(), profile.value(), version);
        if (row.isEmpty()) {
            throw AdminApiException.notFound("profile_not_found",
                    "no such profile '" + profile.value() + "@" + version
                            + "' in tenant '" + tenant.value() + "'");
        }
    }

    /* ---------------------------- json marshalling ------------------------ */

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

    /* ---------------------------- mapping --------------------------------- */

    ChannelBinding toDomain(ChannelBindingEntity entity) {
        RoutingRule routing = readJson(entity.getRoutingJson(), RoutingRule.class);
        SessionPolicy policy = readJson(entity.getSessionPolicyJson(), SessionPolicy.class);
        ChannelEndpointRef endpoint = switch (entity.getEndpointKind()) {
            case "WHATSAPP_SENDER" ->
                    new ChannelEndpointRef.WhatsAppSenderRef(new SenderId(entity.getEndpointId()));
            case "SMS_SENDER" ->
                    new ChannelEndpointRef.SmsSenderRef(new SmsSenderId(entity.getEndpointId()));
            case "CHAT_ENTRY_POINT" ->
                    new ChannelEndpointRef.ChatEntryPointRef(new ChatEntryPointId(entity.getEndpointId()));
            default -> throw new IllegalStateException(
                    "unknown endpoint kind: " + entity.getEndpointKind());
        };
        return new ChannelBinding(
                new BindingId(entity.getId()),
                new Ids.TenantId(entity.getTenantId()),
                endpoint,
                // No explicit version pin (null column) => unpinned "latest",
                // resolved to a concrete version at session start. A pinned
                // row keeps its exact version. See design §5.2.
                entity.getProfileVersion() == null
                        ? Ids.ProfileId.latest(entity.getProfileId())
                        : new Ids.ProfileId(entity.getProfileId(), entity.getProfileVersion()),
                Optional.ofNullable(entity.getProfileVersion()),
                routing,
                policy,
                entity.getPriority(),
                BindingStatus.valueOf(entity.getStatus()),
                toInstant(entity.getCreatedAt()),
                toInstant(entity.getDeactivatedAt()),
                entity.getAuthResolverId(),
                OnUnresolvedPolicy.parse(entity.getOnUnresolved()),
                readStringSet(entity.getAllowedOverrideProfilesJson()),
                OnOverrideDenied.parse(entity.getOnOverrideDenied()),
                entity.getUnresolvedReplyText()
        );
    }

    private Set<String> readStringSet(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) return Set.of();
        try {
            List<String> list = json.readValue(jsonArray, new TypeReference<List<String>>() {});
            return list == null ? Set.of() : Set.copyOf(list);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not decode allowed_override_profiles_json", e);
        }
    }

    private static Instant toInstant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }
}
