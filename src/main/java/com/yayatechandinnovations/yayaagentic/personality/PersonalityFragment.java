package com.yayatechandinnovations.yayaagentic.personality;

import java.util.List;

public record PersonalityFragment(
        String voiceAndTone,
        List<ConversationalRule> rules,
        RefusalTemplates refusals
) {
    public record ConversationalRule(String id, String text) {}

    public record RefusalTemplates(
            String authorizationDenied,
            String unsupportedRequest,
            String missingInformation
    ) {}
}
