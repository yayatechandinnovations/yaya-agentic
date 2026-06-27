package com.yayatechandinnovations.yayaagentic.channel.binding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.channel.ChannelEndpointRef;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelBindingEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelBindingRepository;
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
    private final ObjectMapper json;

    public ChannelBindingService(ChannelBindingRepository repo,
                                 ProfileRepository profiles,
                                 WhatsAppSenderService senders,
                                 ObjectMapper json) {
        this.repo = repo;
        this.profiles = profiles;
        this.senders = senders;
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
                                 int priority) {
        validateEndpointBelongsToTenant(tenant, endpoint);
        validateProfileExists(tenant, profile, profileVersion);

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
        return toDomain(repo.save(entity));
    }

    @Transactional
    public ChannelBinding update(Ids.TenantId tenant,
                                 BindingId id,
                                 Ids.ProfileId profile,
                                 Optional<Integer> profileVersion,
                                 RoutingRule routing,
                                 SessionPolicy sessionPolicy,
                                 int priority,
                                 BindingStatus status) {
        ChannelBindingEntity entity = repo.findByTenantIdAndId(tenant.value(), id.value())
                .orElseThrow(() -> AdminApiException.notFound("binding_not_found",
                        "no such binding " + id.value()));
        validateProfileExists(tenant, profile, profileVersion);

        entity.setProfileId(profile.value());
        entity.setProfileVersion(profileVersion.orElse(null));
        entity.setRoutingJson(writeJson(routing));
        entity.setSessionPolicyJson(writeJson(sessionPolicy));
        entity.setPriority(priority);
        if (status == BindingStatus.DEACTIVATED
                && !entity.getStatus().equals(BindingStatus.DEACTIVATED.name())) {
            entity.setDeactivatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        }
        entity.setStatus(status.name());
        return toDomain(repo.save(entity));
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

    private void validateEndpointBelongsToTenant(Ids.TenantId tenant,
                                                 ChannelEndpointRef endpoint) {
        switch (endpoint) {
            case ChannelEndpointRef.WhatsAppSenderRef senderRef -> {
                // Throws sender_not_found if absent OR if cross-tenant — service
                // already filters by tenant, so cross-tenant lookups return
                // empty and surface as not_found. The §16-mitigated risk is
                // covered: there's no path to bind a tenant's profile to
                // another tenant's sender.
                senders.requireForTenant(tenant, senderRef.id());
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
            default -> throw new IllegalStateException(
                    "unknown endpoint kind: " + entity.getEndpointKind());
        };
        return new ChannelBinding(
                new BindingId(entity.getId()),
                new Ids.TenantId(entity.getTenantId()),
                endpoint,
                new Ids.ProfileId(entity.getProfileId(),
                        entity.getProfileVersion() == null ? 1 : entity.getProfileVersion()),
                Optional.ofNullable(entity.getProfileVersion()),
                routing,
                policy,
                entity.getPriority(),
                BindingStatus.valueOf(entity.getStatus()),
                toInstant(entity.getCreatedAt()),
                toInstant(entity.getDeactivatedAt()),
                entity.getAuthResolverId(),
                OnUnresolvedPolicy.parse(entity.getOnUnresolved())
        );
    }

    private static Instant toInstant(OffsetDateTime odt) {
        return odt == null ? null : odt.toInstant();
    }
}
