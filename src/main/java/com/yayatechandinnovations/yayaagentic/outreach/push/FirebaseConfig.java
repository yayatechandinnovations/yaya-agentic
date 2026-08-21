package com.yayatechandinnovations.yayaagentic.outreach.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.Optional;

/**
 * Wires a single {@link FirebaseMessaging} for the shared Firebase project only
 * when {@code yaya.agentic.push.fcm.enabled=true}
 * (chat-mobile-publishing-and-push-design.md §B.3.3). Absent config leaves the
 * bean unregistered and {@link CompositePushNotifier} falls back to logging, so
 * dev/CI need no Firebase credentials. A named {@link FirebaseApp} keeps this
 * from colliding with any default app on context refresh.
 */
@Configuration
@ConditionalOnProperty(name = "yaya.agentic.push.fcm.enabled", havingValue = "true")
public class FirebaseConfig {

    private static final String APP_NAME = "yaya-fcm";

    @Bean
    public FirebaseMessaging firebaseMessaging(PushProperties props, ResourceLoader resources) {
        PushProperties.Fcm fcm = props.fcm();
        try {
            FirebaseOptions.Builder options = FirebaseOptions.builder()
                    .setCredentials(resolveCredentials(fcm, resources));
            if (StringUtils.hasText(fcm.projectId())) {
                options.setProjectId(fcm.projectId());
            }
            FirebaseApp app = existingApp()
                    .orElseGet(() -> FirebaseApp.initializeApp(options.build(), APP_NAME));
            return FirebaseMessaging.getInstance(app);
        } catch (Exception e) {
            throw new IllegalStateException("FCM init failed (yaya.agentic.push.fcm.*)", e);
        }
    }

    private static GoogleCredentials resolveCredentials(PushProperties.Fcm fcm, ResourceLoader resources)
            throws Exception {
        if (StringUtils.hasText(fcm.credentialsBase64())) {
            byte[] json = Base64.getDecoder().decode(fcm.credentialsBase64().trim());
            try (InputStream in = new ByteArrayInputStream(json)) {
                return GoogleCredentials.fromStream(in);
            }
        }
        if (StringUtils.hasText(fcm.credentialsLocation())) {
            try (InputStream in = resources.getResource(fcm.credentialsLocation()).getInputStream()) {
                return GoogleCredentials.fromStream(in);
            }
        }
        // Ambient GOOGLE_APPLICATION_CREDENTIALS / workload identity.
        return GoogleCredentials.getApplicationDefault();
    }

    private static Optional<FirebaseApp> existingApp() {
        return FirebaseApp.getApps().stream()
                .filter(a -> APP_NAME.equals(a.getName()))
                .findFirst();
    }
}
