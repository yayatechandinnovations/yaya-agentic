/**
 * The system-messaging orchestration layer — ingest → deliver/mediate → respond
 * ({@code docs/design/system-messaging-design.md} §13). Peer to the {@code
 * engine} package: {@link com.yayatechandinnovations.yayaagentic.outreach.OutreachService}
 * is SPI #12, {@link com.yayatechandinnovations.yayaagentic.outreach.RecipientResolver}
 * is SPI #13. Sender config lives in {@code channel.system}.
 */
package com.yayatechandinnovations.yayaagentic.outreach;
