package com.yayatechandinnovations.yayaagentic.outreach;

/**
 * A logical {@link com.yayatechandinnovations.yayaagentic.channel.system.OutreachRecipient}
 * resolved to a concrete delivery target — the user's established chat (§6).
 *
 * @param recipientKey  stable key for the durable outreach-session index
 *                      (e.g. {@code principal:op-42})
 * @param ownerSubject  the verified identity that owns the outreach session; the
 *                      minted session's principal subject, so the transcript is theirs
 * @param entryPointId  the chat entry point to deliver into
 */
public record ResolvedTarget(
        String recipientKey,
        String ownerSubject,
        String entryPointId
) {}
