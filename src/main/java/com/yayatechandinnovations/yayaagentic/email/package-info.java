/**
 * Outbound email — outbox-buffered, provider-pluggable.
 *
 * <p>Services enqueue via {@link com.yayatechandinnovations.yayaagentic.email.OutboxMailQueue}
 * (writes to {@code email_outbox}). The
 * {@link com.yayatechandinnovations.yayaagentic.email.MailOutboxDrainer}
 * polls and hands ready rows to the configured
 * {@link com.yayatechandinnovations.yayaagentic.email.MailSender} bean.
 * Transient sender failures are retried with exponential backoff; permanent
 * failures DEAD-letter for SRE inspection.</p>
 *
 * <p>See {@code docs/specs/user-accounts/TRD.md} §3.4 and §12.5.</p>
 */
package com.yayatechandinnovations.yayaagentic.email;
