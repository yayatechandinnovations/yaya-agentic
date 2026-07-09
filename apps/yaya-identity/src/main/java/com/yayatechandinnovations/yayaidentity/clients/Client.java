package com.yayatechandinnovations.yayaidentity.clients;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;

import java.util.List;

/**
 * A registered client. {@code PUBLIC} uses PKCE with no secret; {@code CONFIDENTIAL}
 * has a secret; {@code SERVICE} is a machine identity (client-credentials).
 * {@code audiences} bind issued tokens to the app(s) they're valid for.
 */
public record Client(String id,
                     String clientId,
                     OrgId org,
                     String name,
                     Type type,
                     List<String> grants,
                     List<String> redirectUris,
                     List<String> scopes,
                     List<String> audiences,
                     boolean requirePkce) {

    public enum Type { PUBLIC, CONFIDENTIAL, SERVICE }
}
