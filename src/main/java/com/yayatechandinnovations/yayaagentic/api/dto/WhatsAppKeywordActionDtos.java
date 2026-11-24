package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.keywords.KeywordAction;

import java.util.List;

/** Wire DTOs for {@code /v1/admin/tenants/{t}/whatsapp-senders/{s}/keyword-actions}. */
public final class WhatsAppKeywordActionDtos {

    private WhatsAppKeywordActionDtos() {}

    /** GET response and PUT body alike: the whole set. */
    public record ActionSet(List<ActionItem> actions) {
        public static ActionSet of(List<KeywordAction> actions) {
            return new ActionSet(actions.stream()
                    .map(a -> new ActionItem(a.prefix(), a.forwardUrl(), a.scope().name())).toList());
        }
    }

    /** {@code scope}: {@code ROUTED} (default when absent) or {@code REPLY} — shared-sender design §8.2. */
    public record ActionItem(String prefix, String forwardUrl, String scope) {
        public ActionItem(String prefix, String forwardUrl) {
            this(prefix, forwardUrl, null);
        }
    }
}
