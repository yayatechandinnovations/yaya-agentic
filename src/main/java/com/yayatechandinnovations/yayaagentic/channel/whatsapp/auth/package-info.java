/**
 * End-user identity policy for the WhatsApp channel. Replaces the hard-coded
 * scope set previously baked into {@link
 * com.yayatechandinnovations.yayaagentic.channel.whatsapp.WhatsAppPhoneAuthenticator}
 * with a per-binding {@link
 * com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth.DefaultPrincipalPolicy}.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §5 (Layer 1: default principal
 * policy) and §3 (non-negotiables: channel-reserved claims, baseline scopes).
 */
package com.yayatechandinnovations.yayaagentic.channel.whatsapp.auth;
