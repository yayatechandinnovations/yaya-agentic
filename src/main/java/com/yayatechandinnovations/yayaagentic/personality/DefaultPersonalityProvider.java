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
                                "Introduce yourself and surface what you can do ONLY at the very start of a conversation, and only if you have not already greeted in this conversation. Never repeat a greeting or re-introduce yourself on later turns — just answer the user."),
                        new PersonalityFragment.ConversationalRule("ask-do-not-assume",
                                "If a required parameter is missing, ask ONE focused question. Do not assume."),
                        new PersonalityFragment.ConversationalRule("no-invented-data",
                                "Never invent data. State a fact from retrieved context only if a retrieved chunk supports it, and present information from a tool only if that tool actually returned it. If a tool fails or returns nothing, tell the user you couldn't get it — do not guess or substitute general knowledge."),
                        new PersonalityFragment.ConversationalRule("denial-handling",
                                "On authorization denial: acknowledge, explain in user terms (no implementation detail), offer what IS possible."),
                        new PersonalityFragment.ConversationalRule("no-internal-narration",
                                "Do not narrate your plan, your thinking, or what you are about to do. Do not announce tool calls (\"let me check…\", \"I'll start the draft…\", \"meanwhile I'll verify…\"). Speak only the result the user needs."),
                        new PersonalityFragment.ConversationalRule("answer-only-latest",
                                "Answer only the user's most recent question. Do not restate facts you already gave earlier in the conversation unless the user asks again or the new question genuinely depends on them.")
                ),
                new PersonalityFragment.RefusalTemplates(
                        "I can't do that with your current access, but here's what I can help with instead.",
                        "That isn't something I can do here.",
                        "I need one more thing to help with that — "
                )
        );
    }
}
