package com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords;

import com.yayatechandinnovations.yayaagentic.api.AdminApiException;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.SenderId;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppSenderService;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppKeywordActionEntity;
import com.yayatechandinnovations.yayaagentic.persistence.WhatsAppKeywordActionRepository;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpEgressPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The keyword actions of a sender ({@code whatsapp_keyword_actions}, V97):
 * admin read/replace, and the hot-path match the inbound worker runs after
 * STOP/START and before binding matching. Longest prefix wins when several
 * match, so «NO MAS TICKETS» is not caught by a bare «NO».
 */
@Service
public class KeywordActionService {

    public static final int MAX_PREFIX_CHARS = 64;
    public static final int MAX_URL_CHARS = 1024;
    public static final int MAX_ACTIONS = 50;

    private final WhatsAppKeywordActionRepository repo;
    private final WhatsAppSenderService senders;
    private final HttpEgressPolicy egress;

    public KeywordActionService(WhatsAppKeywordActionRepository repo, WhatsAppSenderService senders,
                                HttpEgressPolicy egress) {
        this.repo = repo;
        this.senders = senders;
        this.egress = egress;
    }

    /**
     * Words the platform answers itself on a number several workspaces share
     * (whatsapp-shared-sender-design.md §7.2, §17.1): no workspace may claim
     * them there.
     */
    public static final java.util.Set<String> RESERVED_ON_PLATFORM_SENDERS =
            java.util.Set.of("CAMBIAR", "CAMBIAR NEGOCIO", "#NEGOCIO", "STOP", "START");

    public List<KeywordAction> list(Ids.TenantId tenant, SenderId sender) {
        senders.requireUsable(tenant, sender);
        return repo.findByTenantIdAndSenderIdOrderByIdAsc(tenant.value(), sender.value()).stream()
                .filter(WhatsAppKeywordActionEntity::isEnabled)
                .map(KeywordActionService::toAction)
                .toList();
    }

    /**
     * PUT semantics: the given set replaces whatever this workspace had on the
     * sender. Duplicate prefixes collapse to the last. On a platform sender the
     * other workspaces' rows stay, and a ROUTED prefix one of them holds is
     * refused without saying whose it is.
     */
    @Transactional
    public List<KeywordAction> replaceAll(Ids.TenantId tenant, SenderId sender, List<KeywordAction> actions) {
        boolean platform = senders.requireUsable(tenant, sender).isPlatform();
        List<KeywordAction> wanted = actions == null ? List.of() : actions;
        if (wanted.size() > MAX_ACTIONS) {
            throw AdminApiException.badRequest("too_many_actions", "at most " + MAX_ACTIONS + " keyword actions");
        }
        Map<String, KeywordAction> byPrefix = new LinkedHashMap<>();
        for (KeywordAction a : wanted) {
            validate(a, egress != null && egress.allowsPrivateNetworks());
            if (platform && RESERVED_ON_PLATFORM_SENDERS.contains(a.prefix())) {
                throw AdminApiException.badRequest("reserved_keyword",
                        "'" + a.prefix() + "' is answered by the platform on a shared number");
            }
            byPrefix.put(a.prefix(), a);
        }
        if (platform) {
            java.util.Set<String> takenElsewhere = new java.util.HashSet<>();
            for (WhatsAppKeywordActionEntity e
                    : repo.findBySenderIdAndScopeOrderByIdAsc(sender.value(), KeywordAction.Scope.ROUTED.name())) {
                if (!tenant.value().equals(e.getTenantId())) takenElsewhere.add(e.getPrefix());
            }
            for (KeywordAction a : byPrefix.values()) {
                if (a.scope() == KeywordAction.Scope.ROUTED && takenElsewhere.contains(a.prefix())) {
                    throw AdminApiException.conflict("keyword_prefix_taken",
                            "'" + a.prefix() + "' is already used on this shared number — choose another");
                }
            }
        }
        repo.deleteByTenantIdAndSenderId(tenant.value(), sender.value());
        repo.flush();
        List<KeywordAction> saved = new ArrayList<>();
        for (KeywordAction a : byPrefix.values()) {
            WhatsAppKeywordActionEntity row =
                    new WhatsAppKeywordActionEntity(tenant.value(), sender.value(), a.prefix(), a.forwardUrl());
            row.setScope(a.scope().name());
            repo.save(row);
            saved.add(a);
        }
        try {
            repo.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            throw AdminApiException.conflict("keyword_prefix_taken",
                    "a keyword in this set was just taken on this shared number — choose another");
        }
        return saved;
    }

    /** The action whose prefix the (normalised) text starts with as a word; the longest when several do. */
    public Optional<KeywordAction> match(Ids.TenantId tenant, SenderId sender, String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        List<WhatsAppKeywordActionEntity> rows =
                repo.findByTenantIdAndSenderIdOrderByIdAsc(tenant.value(), sender.value());
        if (rows.isEmpty()) return Optional.empty();
        String text = KeywordTextNormalizer.normalize(body);
        return longest(rows, text).map(KeywordActionService::toAction);
    }

    /** A keyword of some workspace on a shared number, and that workspace. */
    public record Owned(Ids.TenantId tenant, KeywordAction action) {}

    /**
     * Router signal 4 (§7.2): the ROUTED keyword the text starts with, across
     * every workspace on the platform sender — the prefix is unique there, so
     * it names its workspace.
     */
    public Optional<Owned> matchRouted(SenderId sender, String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        String text = KeywordTextNormalizer.normalize(body);
        return longest(repo.findBySenderIdAndScopeOrderByIdAsc(sender.value(), KeywordAction.Scope.ROUTED.name()), text)
                .map(e -> new Owned(new Ids.TenantId(e.getTenantId()), toAction(e)));
    }

    /** Workspaces on the platform sender holding a REPLY keyword the text starts with (§8.2). */
    public List<Owned> matchReply(SenderId sender, String body) {
        if (body == null || body.isBlank()) return List.of();
        String text = KeywordTextNormalizer.normalize(body);
        Map<String, WhatsAppKeywordActionEntity> best = new LinkedHashMap<>();
        for (WhatsAppKeywordActionEntity e
                : repo.findBySenderIdAndScopeOrderByIdAsc(sender.value(), KeywordAction.Scope.REPLY.name())) {
            if (!e.isEnabled() || !KeywordTextNormalizer.startsWithWord(text, e.getPrefix())) continue;
            best.merge(e.getTenantId(), e, (a, b) -> b.getPrefix().length() > a.getPrefix().length() ? b : a);
        }
        return best.values().stream()
                .map(e -> new Owned(new Ids.TenantId(e.getTenantId()), toAction(e)))
                .toList();
    }

    /** Ending an assignment removes the workspace's keywords on the number (§11). */
    @Transactional
    public int removeAll(Ids.TenantId tenant, SenderId sender) {
        int n = repo.findByTenantIdAndSenderIdOrderByIdAsc(tenant.value(), sender.value()).size();
        repo.deleteByTenantIdAndSenderId(tenant.value(), sender.value());
        return n;
    }

    private static Optional<WhatsAppKeywordActionEntity> longest(List<WhatsAppKeywordActionEntity> rows, String text) {
        return rows.stream()
                .filter(WhatsAppKeywordActionEntity::isEnabled)
                .filter(e -> KeywordTextNormalizer.startsWithWord(text, e.getPrefix()))
                .max(Comparator.comparingInt(e -> e.getPrefix().length()));
    }

    private static KeywordAction toAction(WhatsAppKeywordActionEntity e) {
        return new KeywordAction(e.getPrefix(), e.getForwardUrl(), KeywordAction.Scope.valueOf(e.getScope()));
    }

    /**
     * The forward carries a service token the install verifies, so the URL
     * must be {@code https}: a plaintext hop would expose the token and let
     * a DNS-rebound target read the inbound. Only the dev flag
     * {@code yaya.agentic.http-tools.allow-private-networks} (a loopback
     * WireMock "install") admits {@code http}.
     */
    static void validate(KeywordAction a, boolean allowPlainHttp) {
        if (a.prefix().length() > MAX_PREFIX_CHARS) {
            throw AdminApiException.badRequest("bad_prefix", "prefix is at most " + MAX_PREFIX_CHARS + " characters");
        }
        if (a.forwardUrl().length() > MAX_URL_CHARS) {
            throw AdminApiException.badRequest("bad_forward_url", "forwardUrl is at most " + MAX_URL_CHARS + " characters");
        }
        URI uri;
        try {
            uri = URI.create(a.forwardUrl());
        } catch (IllegalArgumentException e) {
            throw AdminApiException.badRequest("bad_forward_url", "forwardUrl is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        boolean schemeOk = scheme.equals("https") || (allowPlainHttp && scheme.equals("http"));
        if (!uri.isAbsolute() || uri.getHost() == null || !schemeOk) {
            throw AdminApiException.badRequest("bad_forward_url", allowPlainHttp
                    ? "forwardUrl must be an absolute http(s) URL"
                    : "forwardUrl must be an absolute https URL");
        }
    }
}
