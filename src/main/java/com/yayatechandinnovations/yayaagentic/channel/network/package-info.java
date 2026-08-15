/**
 * Runtime integration for the Corillo network entry point
 * ({@code player_network_v1}) — yaya-agentic-network-chat-runtime-design.md.
 *
 * <p>Holds the hub-broker client, the brokered play-credential cache, and the
 * Bean tool handlers that back the network profile's capabilities. This is an
 * app-adjacent integration, deliberately OUTSIDE {@code core/}: it carries the
 * domain vocabulary (banca, install, play, prize) the framework core must not.
 * The session-scoped banca context and its WM slot keys live in
 * {@code engine.banca} so the engine gate can share them.
 */
package com.yayatechandinnovations.yayaagentic.channel.network;
