package com.yayatechandinnovations.yayaagentic.api.dto;

import com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup.MessagingAccountStatus;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup.OnboardingAttempt;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup.OnboardingAttemptState;
import com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup.WhatsAppMessagingAccount;

import java.time.Instant;

/**
 * Wire DTOs for {@code /v1/admin/tenants/{tenantId}/whatsapp/embedded-signup}.
 * Twilio stays invisible: no subaccount SID, no auth material, no
 * BSP-specific vocabulary crosses this boundary. Attempt failure messages
 * are pre-sanitized server-side.
 */
public final class WhatsAppEmbeddedSignupDtos {

    private WhatsAppEmbeddedSignupDtos() {}

    /** Browser-safe Meta configuration for the Flutter wizard. These ids are
     *  public by nature (they appear in the page's Facebook SDK calls). */
    public record SignupConfigResponse(
            boolean enabled,
            String metaAppId,
            String metaConfigurationId,
            String partnerSolutionId
    ) {}

    /** POST body: the wizard's step-1 form data plus the ids captured from
     *  Meta's Embedded Signup FINISH event. */
    public record OnboardingRequestBody(
            String phoneE164,
            String wabaId,
            String metaPhoneNumberId,
            String displayName,
            WhatsAppSenderDtos.BusinessProfileRequest businessProfile,
            String defaultLocale
    ) {}

    public record OnboardingResponse(
            AttemptView attempt,
            WhatsAppSenderDtos.SenderResponse sender
    ) {}

    public record AttemptView(
            String                 id,
            String                 phoneE164,
            String                 wabaId,
            String                 senderId,
            String                 messagingAccountId,
            OnboardingAttemptState state,
            String                 failureCode,
            String                 failureMessage,
            Instant                createdAt,
            Instant                updatedAt,
            Instant                completedAt
    ) {
        public static AttemptView of(OnboardingAttempt a) {
            return new AttemptView(a.id(), a.phoneE164(), a.wabaId(), a.senderId(),
                    a.messagingAccountId(), a.state(), a.failureCode(), a.failureMessage(),
                    a.createdAt(), a.updatedAt(), a.completedAt());
        }
    }

    /** Messaging-account view. Deliberately excludes the Twilio subaccount
     *  SID and any credential material. */
    public record MessagingAccountView(
            String                 id,
            String                 wabaId,
            MessagingAccountStatus status,
            Instant                createdAt,
            Instant                updatedAt
    ) {
        public static MessagingAccountView of(WhatsAppMessagingAccount a) {
            return new MessagingAccountView(a.id(), a.wabaId(), a.status(),
                    a.createdAt(), a.updatedAt());
        }
    }
}
