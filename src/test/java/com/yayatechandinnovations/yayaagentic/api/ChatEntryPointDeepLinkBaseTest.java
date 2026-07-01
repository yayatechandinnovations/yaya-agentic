package com.yayatechandinnovations.yayaagentic.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The emitted deep link must always carry the canonical {@code /e/} entry path
 * (chat-channel-design.md §11) no matter how the operator set
 * {@code yaya.agentic.chat.deep-link-base} — a base of just the host produced
 * {@code …/e_<handle>}, which the app could not route.
 */
class ChatEntryPointDeepLinkBaseTest {

    @Test
    void normalizesAllReasonableBasesToASingleTrailingEPath() {
        String handle = "e_abc123";
        assertThat(ChatEntryPointController.normalizeDeepLinkBase("https://chat.example.com/e/") + handle)
                .isEqualTo("https://chat.example.com/e/e_abc123");
        // host only (the misconfiguration that shipped) — must gain the /e/ segment
        assertThat(ChatEntryPointController.normalizeDeepLinkBase("https://chat.example.com") + handle)
                .isEqualTo("https://chat.example.com/e/e_abc123");
        assertThat(ChatEntryPointController.normalizeDeepLinkBase("https://chat.example.com/") + handle)
                .isEqualTo("https://chat.example.com/e/e_abc123");
        // trailing /e without the slash
        assertThat(ChatEntryPointController.normalizeDeepLinkBase("https://chat.example.com/e") + handle)
                .isEqualTo("https://chat.example.com/e/e_abc123");
        // redundant trailing slashes
        assertThat(ChatEntryPointController.normalizeDeepLinkBase("https://chat.example.com//") + handle)
                .isEqualTo("https://chat.example.com/e/e_abc123");
    }

    @Test
    void blankOrNullFallsBackToTheDefaultBase() {
        assertThat(ChatEntryPointController.normalizeDeepLinkBase(null))
                .isEqualTo("https://chat.yaya.app/e/");
        assertThat(ChatEntryPointController.normalizeDeepLinkBase("   "))
                .isEqualTo("https://chat.yaya.app/e/");
    }
}
