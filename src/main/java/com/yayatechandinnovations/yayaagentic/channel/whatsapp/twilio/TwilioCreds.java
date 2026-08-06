package com.yayatechandinnovations.yayaagentic.channel.whatsapp.twilio;

import com.yayatechandinnovations.yayaagentic.channel.ProviderCreds;
import com.yayatechandinnovations.yayaagentic.channel.ProviderId;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Twilio API credentials wrapped as a {@link ProviderCreds}. Resolved per
 * call (not held as ambient state) so the v1 platform-singleton resolver and
 * the per-tenant subaccount resolver share the same wire-up — see
 * {@code whatsapp-channel-design.md} §7.2, §7.3.
 *
 * <p>Two auth shapes:
 * <ul>
 *   <li><b>API key pair</b> ({@code apiKeySid} + {@code apiKeySecret}) — the
 *       platform parent account's recommended auth.</li>
 *   <li><b>Account auth token</b> ({@code authToken}) — tenant subaccounts
 *       provisioned by the Tech Provider flow carry only their SID + auth
 *       token; no per-subaccount API key is minted.</li>
 * </ul>
 * At least one shape must be present; {@link #basicAuthHeader()} prefers the
 * API key pair.
 *
 * <p>{@code messagingServiceSid} is optional and only meaningful for the
 * platform account — a parent Messaging Service SID is not valid inside a
 * subaccount, so subaccount creds leave it null and dispatch falls back to
 * {@code From} addressing.
 */
public record TwilioCreds(
        String accountSid,
        String apiKeySid,
        String apiKeySecret,
        String messagingServiceSid,
        String authToken
) implements ProviderCreds {

    public TwilioCreds {
        if (accountSid == null || accountSid.isBlank()) {
            throw new IllegalArgumentException("accountSid required");
        }
        boolean hasApiKey = notBlank(apiKeySid) && notBlank(apiKeySecret);
        boolean hasAuthToken = notBlank(authToken);
        if (!hasApiKey && !hasAuthToken) {
            throw new IllegalArgumentException(
                    "either an API key pair or an account auth token is required");
        }
    }

    /** Back-compat: platform-account creds authenticated with an API key pair. */
    public TwilioCreds(String accountSid, String apiKeySid, String apiKeySecret,
                       String messagingServiceSid) {
        this(accountSid, apiKeySid, apiKeySecret, messagingServiceSid, null);
    }

    /** Subaccount creds: SID + auth token only, no Messaging Service. */
    public static TwilioCreds forSubaccount(String subaccountSid, String authToken) {
        return new TwilioCreds(subaccountSid, null, null, null, authToken);
    }

    /** The HTTP Basic header value for Twilio API calls with these creds. */
    public String basicAuthHeader() {
        String user;
        String pass;
        if (notBlank(apiKeySid) && notBlank(apiKeySecret)) {
            user = apiKeySid;
            pass = apiKeySecret;
        } else {
            user = accountSid;
            pass = authToken;
        }
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public ProviderId provider() {
        return ProviderId.TWILIO;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
