/**
 * Meta Embedded Signup + Twilio Tech Provider onboarding for WhatsApp
 * senders. Extends {@code whatsapp-channel-design.md} §7.3's reserved seam:
 * each customer business/WABA gets one dedicated Twilio subaccount under the
 * platform's parent account, and the sender is registered through the Channel
 * Senders v2 API with the WABA captured by Meta's Embedded Signup popup.
 * Tenants still never see, hold, or configure Twilio credentials.
 */
package com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup;
