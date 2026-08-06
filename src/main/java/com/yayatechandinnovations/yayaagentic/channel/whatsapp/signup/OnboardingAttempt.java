package com.yayatechandinnovations.yayaagentic.channel.whatsapp.signup;

import com.yayatechandinnovations.yayaagentic.core.Ids;

import java.time.Instant;

/**
 * Runtime view of one row of {@code whatsapp_onboarding_attempts}. The
 * failure message is already sanitized ({@link OnboardingErrorSanitizer}) —
 * safe to surface to tenant operators as-is.
 */
public record OnboardingAttempt(
        String                id,
        Ids.TenantId          tenant,
        String                phoneE164,
        String                wabaId,
        String                metaPhoneNumberId,
        String                senderId,
        String                messagingAccountId,
        OnboardingAttemptState state,
        String                failureCode,
        String                failureMessage,
        Instant               createdAt,
        Instant               updatedAt,
        Instant               completedAt
) {}
