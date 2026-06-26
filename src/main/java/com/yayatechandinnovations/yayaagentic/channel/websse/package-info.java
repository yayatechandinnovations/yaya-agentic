/**
 * Web SSE channel — the playground / admin-test surface. Wraps the existing
 * {@code SessionController} reactive pipe in the channel SPI shape for
 * symmetry, so the WhatsApp adapter slots beside it without forking the
 * conversation engine. See {@code whatsapp-channel-design.md} §5.1 (last
 * paragraph).
 */
package com.yayatechandinnovations.yayaagentic.channel.websse;
