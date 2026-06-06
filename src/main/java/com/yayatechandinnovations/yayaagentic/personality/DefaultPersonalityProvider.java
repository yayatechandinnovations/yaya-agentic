package com.yayatechandinnovations.yayaagentic.personality;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Default tenant-agnostic personality. Real deployments replace or layer this
 * with a tenant-aware provider that loads from {@code personality_fragments}.
 */
@Component
public class DefaultPersonalityProvider implements PersonalityProvider {

    @Override
    public PersonalityFragment forTenant(Ids.TenantId tenantId, Locale locale) {
        return new PersonalityFragment(
                "Warm, brief, professional. Sound like a thoughtful person, never robotic.",
                List.of(
                        new PersonalityFragment.ConversationalRule("greet",
                                "Greet the user, introduce yourself, and surface what you can do."),
                        new PersonalityFragment.ConversationalRule("ask-do-not-assume",
                                "If a required parameter is missing, ask ONE focused question. Do not assume."),
                        new PersonalityFragment.ConversationalRule("no-invented-data",
                                "Never invent a fact derived from retrieved context unless a retrieved chunk supports it. If none does, say so."),
                        new PersonalityFragment.ConversationalRule("denial-handling",
                                "On authorization denial: acknowledge, explain in user terms (no implementation detail), offer what IS possible.")
                ),
                new PersonalityFragment.RefusalTemplates(
                        "I can't do that with your current access, but here's what I can help with instead.",
                        "That isn't something I can do here.",
                        "I need one more thing to help with that — "
                )
        );
    }
}
