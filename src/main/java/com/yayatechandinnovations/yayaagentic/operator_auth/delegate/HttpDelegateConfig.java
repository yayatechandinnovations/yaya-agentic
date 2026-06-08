package com.yayatechandinnovations.yayaagentic.operator_auth.delegate;

import java.time.Duration;

/**
 * Top-level delegate config — what the admin UI edits and the
 * authenticator reads. See {@code docs/design/operator-auth-design.md} §5.7.
 *
 * <p>{@code sharedSecret} is plaintext at this level; storage handles
 * encryption via {@code SecretCipher}.</p>
 */
public record HttpDelegateConfig(
        boolean enabled,
        String url,
        String sharedSecret,
        Duration timeout,
        boolean requireHttps,
        RequestShape request,
        SuccessCriteria success,
        IdentityMapping identity,
        FailureMapping failure
) {
    public static HttpDelegateConfig disabled() {
        return new HttpDelegateConfig(
                false, null, null,
                Duration.ofSeconds(5), true,
                RequestShape.defaults(),
                SuccessCriteria.defaults(),
                IdentityMapping.defaults(),
                FailureMapping.defaults());
    }

    public HttpDelegateConfig {
        if (timeout == null) timeout = Duration.ofSeconds(5);
        if (request == null) request = RequestShape.defaults();
        if (success == null) success = SuccessCriteria.defaults();
        if (identity == null) identity = IdentityMapping.defaults();
        if (failure == null) failure = FailureMapping.defaults();
    }

    public HttpDelegateConfig withRequest(RequestShape r) {
        return new HttpDelegateConfig(enabled, url, sharedSecret, timeout, requireHttps, r, success, identity, failure);
    }

    public HttpDelegateConfig withSuccess(SuccessCriteria s) {
        return new HttpDelegateConfig(enabled, url, sharedSecret, timeout, requireHttps, request, s, identity, failure);
    }

    public HttpDelegateConfig withIdentity(IdentityMapping i) {
        return new HttpDelegateConfig(enabled, url, sharedSecret, timeout, requireHttps, request, success, i, failure);
    }

    public HttpDelegateConfig withFailure(FailureMapping f) {
        return new HttpDelegateConfig(enabled, url, sharedSecret, timeout, requireHttps, request, success, identity, f);
    }
}
