/**
 * Channel auth resolvers — turn a verified channel identity (today: a
 * WhatsApp phone) into a tenant-issued JWT that the existing
 * {@code Authenticator} chain verifies.
 *
 * <p>Replaces the W-A1 → W-A9 identity SPI
 * ({@code PhoneToPrincipalResolver}, {@code PrincipalPolicyMaterializer},
 * {@code DefaultPrincipalPolicy}) — see
 * {@code docs/design/channel-auth-resolver-design.md}.
 */
package com.yayatechandinnovations.yayaagentic.channel.auth;
