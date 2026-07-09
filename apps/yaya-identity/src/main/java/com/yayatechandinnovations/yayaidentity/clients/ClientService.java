package com.yayatechandinnovations.yayaidentity.clients;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.outbox.OutboxRelay;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Client registration and secret rotation (design §5.6, §9.3). Service accounts
 * (client-credentials + token-exchange) are the {@code SERVICE_TOKEN} relying
 * parties consume. Secrets are stored hashed; rotation re-hashes.
 */
@Service
public class ClientService {

    private final ClientRepository clients;
    private final PasswordEncoder encoder;
    private final OutboxRelay outbox;

    public ClientService(ClientRepository clients, PasswordEncoder encoder, OutboxRelay outbox) {
        this.clients = clients;
        this.encoder = encoder;
        this.outbox = outbox;
    }

    @Transactional
    public Client registerPublicClient(String clientId, List<String> redirectUris, List<String> scopes) {
        return save(clientId, null, null, Client.Type.PUBLIC, null,
                List.of(AuthorizationGrantType.AUTHORIZATION_CODE.getValue(),
                        AuthorizationGrantType.REFRESH_TOKEN.getValue()),
                redirectUris, scopes, List.of(), true);
    }

    @Transactional
    public Client registerServiceAccount(OrgId org, String clientId, String rawSecret,
                                         List<String> audiences, List<String> scopes) {
        return save(clientId, org == null ? null : org.value(), clientId, Client.Type.SERVICE,
                encoder.encode(rawSecret),
                List.of(AuthorizationGrantType.CLIENT_CREDENTIALS.getValue(),
                        AuthorizationGrantType.TOKEN_EXCHANGE.getValue()),
                List.of(), scopes, audiences, false);
    }

    @Transactional
    public Client rotateSecret(String clientId, String newRawSecret) {
        ClientEntity e = clients.findByClientId(clientId)
                .orElseThrow(() -> new IllegalArgumentException("no such client: " + clientId));
        e.setSecretHash(encoder.encode(newRawSecret));
        outbox.append("client", clientId, "client.secret_rotated", "{\"clientId\":\"" + clientId + "\"}");
        return e.toDomain();
    }

    @Transactional(readOnly = true)
    public Optional<Client> byClientId(String clientId) {
        return clients.findByClientId(clientId).map(ClientEntity::toDomain);
    }

    @Transactional(readOnly = true)
    public List<Client> list() {
        return clients.findAll().stream().map(ClientEntity::toDomain).toList();
    }

    private Client save(String clientId, String orgId, String name, Client.Type type, String secretHash,
                        List<String> grants, List<String> redirectUris, List<String> scopes,
                        List<String> audiences, boolean requirePkce) {
        ClientEntity e = new ClientEntity(
                "cli_" + UUID.randomUUID().toString().replace("-", ""),
                clientId, orgId, name, type, secretHash, grants, redirectUris, scopes, audiences,
                requirePkce, Instant.now());
        clients.save(e);
        return e.toDomain();
    }
}
