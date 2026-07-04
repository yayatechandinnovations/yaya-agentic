/**
 * Trusted integration senders — an external application (POS/ERP/back-office)
 * as a third participant that injects text/media messages into a user's
 * conversation. Config lives here; the ingest → deliver → respond orchestration
 * lives in {@code com.yayatechandinnovations.yayaagentic.outreach}.
 *
 * <p>Design: {@code docs/design/system-messaging-design.md} §3 (config),
 * §4 (trust), §6 (addressing), §7 (mediation).
 */
package com.yayatechandinnovations.yayaagentic.channel.system;
