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

    public List<KeywordAction> list(Ids.TenantId tenant, SenderId sender) {
        senders.requireForTenant(tenant, sender);
        return repo.findByTenantIdAndSenderIdOrderByIdAsc(tenant.value(), sender.value()).stream()
                .filter(WhatsAppKeywordActionEntity::isEnabled)
                .map(e -> new KeywordAction(e.getPrefix(), e.getForwardUrl()))
                .toList();
    }

    /** PUT semantics: the given set replaces whatever the sender had. Duplicate prefixes collapse to the last. */
    @Transactional
    public List<KeywordAction> replaceAll(Ids.TenantId tenant, SenderId sender, List<KeywordAction> actions) {
        senders.requireForTenant(tenant, sender);
        List<KeywordAction> wanted = actions == null ? List.of() : actions;
        if (wanted.size() > MAX_ACTIONS) {
            throw AdminApiException.badRequest("too_many_actions", "at most " + MAX_ACTIONS + " keyword actions");
        }
        Map<String, KeywordAction> byPrefix = new LinkedHashMap<>();
        for (KeywordAction a : wanted) {
            validate(a, egress != null && egress.allowsPrivateNetworks());
            byPrefix.put(a.prefix(), a);
        }
        repo.deleteByTenantIdAndSenderId(tenant.value(), sender.value());
        repo.flush();
        List<KeywordAction> saved = new ArrayList<>();
        for (KeywordAction a : byPrefix.values()) {
            repo.save(new WhatsAppKeywordActionEntity(tenant.value(), sender.value(), a.prefix(), a.forwardUrl()));
            saved.add(a);
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
        return rows.stream()
                .filter(WhatsAppKeywordActionEntity::isEnabled)
                .filter(e -> KeywordTextNormalizer.startsWithWord(text, e.getPrefix()))
                .max(Comparator.comparingInt(e -> e.getPrefix().length()))
                .map(e -> new KeywordAction(e.getPrefix(), e.getForwardUrl()));
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
