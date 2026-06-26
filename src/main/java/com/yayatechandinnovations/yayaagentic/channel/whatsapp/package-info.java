/**
 * WhatsApp-specific channel implementation — the sender aggregate, its state
 * machine, the BSP-specific business profile shape, the sender-side service
 * and controllers, and (in later milestones) the dispatch worker, the phone
 * authenticator, the entity-memory capture, and the audio transcriber.
 *
 * <p>The split between {@code channel/} (BSP-agnostic SPIs) and this package
 * (WhatsApp-specific) lets a future channel kind (SMS, Telegram) drop in
 * beside without churn here. See {@code whatsapp-channel-design.md} §6.
 */
package com.yayatechandinnovations.yayaagentic.channel.whatsapp;
