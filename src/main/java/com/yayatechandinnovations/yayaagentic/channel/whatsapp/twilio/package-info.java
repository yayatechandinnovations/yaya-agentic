/**
 * Twilio — the v1 BSP. Holds the platform-singleton config + creds resolver,
 * the {@code MessagingChannelProvider} implementation (lands in W2 with the
 * sender register/sync calls), and the inbound signature verifier (W4).
 *
 * <p>Tenants never see Twilio surface — see
 * {@code whatsapp-channel-design.md} §7 (platform-singleton decision) and the
 * trust principle's third corollary.
 */
package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;
