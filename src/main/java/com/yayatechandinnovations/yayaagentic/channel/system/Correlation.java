package com.yayatechandinnovations.yayaagentic.channel.system;

/**
 * The originator identity plus the opaque handle a reply must route back to.
 * The crux of the response loop (§8): {@code originatorRef} is understood only
 * by the external system (a callback URL id, a queue key, a ticket id). The user
 * never sees it, the LLM never invents it, and the engine pins it onto the
 * response tool call — so a reply reaches exactly whoever sent the message.
 *
 * @param originatorName display name shown to the user ("Cash Register #3");
 *                       treated as an untrusted display string (§9)
 * @param originatorRef  opaque routing handle the response tool calls back with
 * @param subject        short topic ("cash closure 2026-07-03 18:00")
 */
public record Correlation(
        String originatorName,
        String originatorRef,
        String subject
) {
    public Correlation {
        originatorName = originatorName == null ? "" : originatorName.trim();
        originatorRef = originatorRef == null ? "" : originatorRef.trim();
        subject = subject == null ? "" : subject.trim();
    }
}
