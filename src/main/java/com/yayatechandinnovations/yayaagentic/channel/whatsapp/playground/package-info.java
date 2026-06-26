/**
 * Playground simulated sender. A per-workspace {@code snd_playground_sim__}
 * sender that exists in {@code whatsapp_senders} as a regular row but bypasses
 * the Twilio surface — outbound goes to an in-memory sink the admin UI polls,
 * inbound is forged by the playground controller without signature
 * verification. See {@code whatsapp-channel-design.md} §15.3.
 */
package com.yayatechandinnovations.yayaagentic.channel.whatsapp.playground;
