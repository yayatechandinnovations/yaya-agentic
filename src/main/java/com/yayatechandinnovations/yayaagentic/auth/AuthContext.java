package com.yayatechandinnovations.yayaagentic.auth;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.core.Principal;

import java.util.Map;
import java.util.Optional;

/**
 * Input to {@link Authenticator} and a piece of context handed to
 * {@link ProfileResolver} chains.
 */
public record AuthContext(
        Ids.TenantId tenant,
        Map<String, String> headers,
        Optional<Principal> alreadyVerified
) {}
