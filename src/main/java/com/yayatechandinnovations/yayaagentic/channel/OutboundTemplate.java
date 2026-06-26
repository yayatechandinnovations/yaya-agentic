package com.yayatechandinnovations.yayaagentic.channel;

import java.util.List;
import java.util.Map;

/**
 * BSP-bound templated message (used outside the 24h service window OR for
 * any push-style re-engagement). The body content is BSP-approved at config
 * time; this record carries the placeholder substitutions only.
 *
 * @param recipientE164    destination phone in E.164
 * @param templateName     stable name on our side ({@code whatsapp_templates.name})
 * @param locale           BCP-47 locale of the approved variant
 * @param variables        ordered placeholder substitutions ({{1}}, {{2}}, …)
 * @param providerHints    BSP-specific knobs (e.g. button URL overrides for
 *                         button templates)
 */
public record OutboundTemplate(
        String              recipientE164,
        String              templateName,
        String              locale,
        List<String>        variables,
        Map<String, Object> providerHints
) {

    public OutboundTemplate {
        if (recipientE164 == null || recipientE164.isBlank()) {
            throw new IllegalArgumentException("recipientE164 must not be blank");
        }
        if (templateName == null || templateName.isBlank()) {
            throw new IllegalArgumentException("templateName must not be blank");
        }
        if (locale == null || locale.isBlank()) {
            throw new IllegalArgumentException("locale must not be blank");
        }
        variables     = variables     == null ? List.of() : List.copyOf(variables);
        providerHints = providerHints == null ? Map.of() : Map.copyOf(providerHints);
    }
}
