package com.yayatechandinnovations.yayaagentic.auth.stepup;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Mechanism the engine should use to upgrade a Principal's trust level. The
 * variants here are stable wire-shapes — the actual {@code StepUpProvider}
 * implementations land in W-A5+ and dispatch on these.
 *
 * <p>{@code OtpEmail} and {@code OtpSmsSecondary} both deliver a single-use
 * code over a channel OTHER than the WhatsApp sender we're elevating. The
 * tenant pre-configures the secondary channel; an unconfigured channel is a
 * startup-time error (W-A5).
 *
 * <p>{@code DeepLink} mints a session-and-audience-bound JWT, hands the URL to
 * the user, and elevates once the tenant's web app POSTs back a verified
 * completion (W-A6).
 *
 * <p>{@code Custom(providerId)} delegates to a tenant-implemented
 * {@code StepUpProvider} bean — used for FIDO2 redirects, voice biometrics,
 * etc. The id is matched against the provider registry at runtime.
 *
 * <p>See {@code whatsapp-channel-auth-design.md} §6.1 + §6.3.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StepUpMethod.OtpEmail.class,        name = "OtpEmail"),
        @JsonSubTypes.Type(value = StepUpMethod.OtpSmsSecondary.class, name = "OtpSmsSecondary"),
        @JsonSubTypes.Type(value = StepUpMethod.DeepLink.class,        name = "DeepLink"),
        @JsonSubTypes.Type(value = StepUpMethod.Custom.class,          name = "Custom"),
})
public sealed interface StepUpMethod
        permits StepUpMethod.OtpEmail,
                StepUpMethod.OtpSmsSecondary,
                StepUpMethod.DeepLink,
                StepUpMethod.Custom {

    record OtpEmail() implements StepUpMethod {}

    record OtpSmsSecondary() implements StepUpMethod {}

    record DeepLink() implements StepUpMethod {}

    /** Routes to a tenant-implemented {@code StepUpProvider} bean. */
    record Custom(String providerId) implements StepUpMethod {
        public Custom {
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("Custom.providerId is required");
            }
        }
    }
}
