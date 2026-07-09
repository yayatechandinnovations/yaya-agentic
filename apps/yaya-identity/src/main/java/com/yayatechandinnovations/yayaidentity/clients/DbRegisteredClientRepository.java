package com.yayatechandinnovations.yayaidentity.clients;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Adapts the DB {@code clients} table to Spring Authorization Server's
 * {@link RegisteredClientRepository}. This is the seam that makes client
 * registration DB-backed. The custom {@code settings.client.audiences} client
 * setting carries the token {@code aud} binding, read by the token customizer.
 */
@Component
public class DbRegisteredClientRepository implements RegisteredClientRepository {

    public static final String AUDIENCES_SETTING = "settings.client.audiences";

    private final ClientRepository clients;

    public DbRegisteredClientRepository(ClientRepository clients) {
        this.clients = clients;
    }

    @Override
    @Transactional(readOnly = true)
    public RegisteredClient findById(String id) {
        return clients.findById(id).map(this::toRegistered).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public RegisteredClient findByClientId(String clientId) {
        return clients.findByClientId(clientId).map(this::toRegistered).orElse(null);
    }

    // SAS only reads clients in the flows we support; save() exists for completeness
    // (e.g. dynamic registration) and upserts the core fields.
    @Override
    @Transactional
    public void save(RegisteredClient rc) {
        ClientEntity existing = clients.findByClientId(rc.getClientId()).orElse(null);
        boolean pkce = rc.getClientSettings().isRequireProofKey();
        Client.Type type = rc.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
                ? Client.Type.PUBLIC : Client.Type.CONFIDENTIAL;
        List<String> grants = rc.getAuthorizationGrantTypes().stream()
                .map(AuthorizationGrantType::getValue).toList();
        ClientEntity e = new ClientEntity(
                existing != null ? existing.getId() : "cli_" + UUID.randomUUID().toString().replace("-", ""),
                rc.getClientId(), null, rc.getClientName(), type, rc.getClientSecret(),
                grants, List.copyOf(rc.getRedirectUris()), List.copyOf(rc.getScopes()),
                List.of(), pkce, Instant.now());
        clients.save(e);
    }

    private RegisteredClient toRegistered(ClientEntity e) {
        RegisteredClient.Builder b = RegisteredClient.withId(e.getId())
                .clientId(e.getClientId())
                .clientName(e.getClientId());

        if (e.type() == Client.Type.PUBLIC) {
            b.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        } else {
            b.clientSecret(e.getSecretHash());
            b.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
            b.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        }

        e.getGrants().forEach(g -> b.authorizationGrantType(new AuthorizationGrantType(g)));
        e.getRedirectUris().forEach(b::redirectUri);
        e.getScopes().forEach(b::scope);

        ClientSettings.Builder settings = ClientSettings.builder()
                .requireProofKey(e.isRequirePkce())
                .requireAuthorizationConsent(false);
        if (!e.getAudiences().isEmpty()) {
            settings.setting(AUDIENCES_SETTING, e.getAudiences());
        }
        b.clientSettings(settings.build());
        b.tokenSettings(TokenSettings.builder()
                .accessTokenTimeToLive(Duration.ofMinutes(15))   // FR-1.3: short-lived
                .build());
        return b.build();
    }
}
