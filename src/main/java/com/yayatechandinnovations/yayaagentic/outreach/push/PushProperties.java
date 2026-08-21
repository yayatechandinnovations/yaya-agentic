package com.yayatechandinnovations.yayaagentic.outreach.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Push-transport settings bound from {@code yaya.agentic.push}. Only the FCM
 * transport is wired for now (system-messaging-design.md §11; SM-3, shared
 * Firebase project — chat-mobile-publishing-and-push-design.md §B.3.3). Absent
 * or {@code fcm.enabled=false} config leaves the transport unwired and
 * {@link CompositePushNotifier} logs the wake instead — so local dev and CI run
 * with zero Firebase setup.
 */
@ConfigurationProperties(prefix = "yaya.agentic.push")
public record PushProperties(Fcm fcm) {

    public Fcm fcm() {
        return fcm == null ? Fcm.disabled() : fcm;
    }

    /**
     * One platform-level Firebase service account (shared project). The
     * credential JSON is resolved, in order: {@code credentialsBase64} (for
     * secret-manager/env injection without a file), then {@code
     * credentialsLocation} (a Spring resource location, e.g.
     * {@code file:/etc/yaya/fcm.json} or {@code classpath:fcm.json}), then the
     * ambient {@code GOOGLE_APPLICATION_CREDENTIALS}. {@code projectId} is
     * optional — the SDK infers it from the credential when omitted.
     */
    public record Fcm(
            boolean enabled,
            String projectId,
            String credentialsLocation,
            String credentialsBase64
    ) {
        public static Fcm disabled() {
            return new Fcm(false, null, null, null);
        }
    }
}
