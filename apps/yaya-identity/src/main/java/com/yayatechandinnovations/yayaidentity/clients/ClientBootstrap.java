package com.yayatechandinnovations.yayaidentity.clients;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seeds the bootstrap clients into the DB on first boot (idempotent): the public
 * PKCE console client and, when a secret is configured, the client-credentials +
 * token-exchange service client (the agent). Replaces the former in-memory seed;
 * real client registration goes through {@link ClientService} / the admin API.
 */
@Component
public class ClientBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ClientBootstrap.class);

    private final ClientService clients;
    private final ClientRepository repo;
    private final String consoleId;
    private final String consoleRedirect;
    private final String serviceId;
    private final String serviceSecret;
    private final List<String> serviceAudiences;

    public ClientBootstrap(
            ClientService clients, ClientRepository repo,
            @Value("${yaya.identity.bootstrap.console-client.id:yaya-console}") String consoleId,
            @Value("${yaya.identity.bootstrap.console-client.redirect-uri:http://127.0.0.1:9999/callback}")
            String consoleRedirect,
            @Value("${yaya.identity.bootstrap.service-client.id:yaya-agentic}") String serviceId,
            @Value("${yaya.identity.bootstrap.service-client.secret:}") String serviceSecret,
            @Value("${yaya.identity.bootstrap.service-client.audiences:yaya-sales}") String serviceAudiences) {
        this.clients = clients;
        this.repo = repo;
        this.consoleId = consoleId;
        this.consoleRedirect = consoleRedirect;
        this.serviceId = serviceId;
        this.serviceSecret = serviceSecret;
        this.serviceAudiences = List.of(serviceAudiences.split(","));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!repo.existsByClientId(consoleId)) {
            clients.registerPublicClient(consoleId, List.of(consoleRedirect), List.of("openid", "profile"));
            log.info("Seeded public console client '{}'", consoleId);
        }
        if (serviceSecret != null && !serviceSecret.isBlank() && !repo.existsByClientId(serviceId)) {
            clients.registerServiceAccount(null, serviceId, serviceSecret, serviceAudiences,
                    List.of("identity.pdp"));
            log.info("Seeded service client '{}' (aud={})", serviceId, serviceAudiences);
        }
    }
}
