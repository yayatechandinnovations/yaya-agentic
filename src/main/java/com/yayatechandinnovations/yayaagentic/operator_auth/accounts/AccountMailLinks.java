package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Builds the one-click URLs the account-lifecycle emails embed in their
 * bodies. Centralised here so all three flows (verify / reset / invite)
 * stay consistent and the {@code yaya.agentic.public-base-url} knob has
 * a single point of consumption.
 *
 * <p>Each link maps to a Flutter SPA route the {@code yayaagenticweb} app
 * already serves:
 * <ul>
 *   <li>{@code /verify?token=…} — {@code VerifyEmailLandingScreen}</li>
 *   <li>{@code /reset-password?token=…} — {@code ResetPasswordScreen}</li>
 *   <li>{@code /accept-invitation?token=…} — {@code AcceptInvitationScreen}</li>
 * </ul>
 * </p>
 */
@Component
public class AccountMailLinks {

    private final String baseUrl;

    public AccountMailLinks(YayaAgenticProperties props) {
        String configured = props.publicBaseUrl();
        this.baseUrl = stripTrailingSlash(
                configured == null || configured.isBlank()
                        ? "http://localhost:8080"
                        : configured);
    }

    public String verifyUrl(String rawToken) {
        return build("/verify", rawToken);
    }

    public String resetPasswordUrl(String rawToken) {
        return build("/reset-password", rawToken);
    }

    public String acceptInvitationUrl(String rawToken) {
        return build("/accept-invitation", rawToken);
    }

    private String build(String path, String rawToken) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .path(path)
                .queryParam("token", rawToken)
                .build()
                .toUriString();
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
