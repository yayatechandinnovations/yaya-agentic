package com.yayatechandinnovations.yayaagentic.channel.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.auth.secrets.JwtSecretCipher;
import com.yayatechandinnovations.yayaagentic.persistence.ChannelAuthResolverEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantEntity;
import com.yayatechandinnovations.yayaagentic.persistence.TenantRepository;
import com.yayatechandinnovations.yayaagentic.tool.dispatch.HttpEgressPolicy;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Turns a {@code channel_auth_resolvers} row of {@code kind=HTTP} into a
 * configured {@link HttpAuthResolver}. Resolves the tenant
 * {@code host_base_url}, composes the absolute URL, decrypts the
 * service secret via {@link JwtSecretCipher}, and builds a per-resolver
 * {@link WebClient} with the row's timeout applied at both connect and
 * read levels.
 *
 * <p>See {@code docs/design/channel-auth-resolver-design.md} §5.2, §6.4.
 */
@Component
public class HttpAuthResolverFactory {

    private final TenantRepository tenants;
    private final JwtSecretCipher cipher;
    private final HttpEgressPolicy egressPolicy;
    private final ObjectMapper json;
    private final Clock clock;

    public HttpAuthResolverFactory(TenantRepository tenants,
                                   JwtSecretCipher cipher,
                                   HttpEgressPolicy egressPolicy,
                                   ObjectMapper json,
                                   Clock clock) {
        this.tenants = tenants;
        this.cipher = cipher;
        this.egressPolicy = egressPolicy;
        this.json = json;
        this.clock = clock;
    }

    public HttpAuthResolver build(ChannelAuthResolverEntity row) {
        if (!"HTTP".equals(row.getKind())) {
            throw new IllegalArgumentException(
                    "HttpAuthResolverFactory cannot build kind=" + row.getKind());
        }
        TenantEntity tenant = tenants.findById(row.getTenantId())
                .orElseThrow(() -> new AuthResolverException("config_error",
                        "resolver '" + row.getName() + "' references unknown tenant '"
                                + row.getTenantId() + "'"));
        String hostBaseUrl = tenant.getHostBaseUrl();
        if (hostBaseUrl == null || hostBaseUrl.isBlank()) {
            throw new AuthResolverException("config_error",
                    "tenant '" + row.getTenantId() + "' has no host_base_url — "
                            + "set it before enabling HTTP auth resolvers");
        }
        URI url = HttpAuthResolver.composeUrl(hostBaseUrl, row.getHttpPath());

        HttpAuthResolver.ServiceAuthKind kind = parseKind(row.getServiceAuthKind());
        String secret = null;
        if (kind != HttpAuthResolver.ServiceAuthKind.NONE
                && row.getServiceSecretCiphertext() != null) {
            secret = cipher.decrypt(row.getServiceSecretCiphertext(), row.getServiceSecretIv());
        }
        if (kind == HttpAuthResolver.ServiceAuthKind.BEARER && secret == null) {
            throw new AuthResolverException("config_error",
                    "resolver '" + row.getName() + "' has BEARER auth but no service_secret stored");
        }
        if (kind == HttpAuthResolver.ServiceAuthKind.SIGNED_JWT && secret == null) {
            throw new AuthResolverException("config_error",
                    "resolver '" + row.getName() + "' has SIGNED_JWT auth but no service_secret stored");
        }

        Duration timeout = Duration.ofMillis(row.getTimeoutMs());
        WebClient client = buildWebClient(timeout);
        return new HttpAuthResolver(
                row.getName(),
                row.getDescription() == null ? row.getName() : row.getDescription(),
                url,
                kind,
                secret,
                timeout,
                client,
                egressPolicy,
                json,
                clock);
    }

    private WebClient buildWebClient(Duration timeout) {
        int millis = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, millis)
                .responseTimeout(timeout)
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(timeout.toMillis(), TimeUnit.MILLISECONDS)));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    private static HttpAuthResolver.ServiceAuthKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) return HttpAuthResolver.ServiceAuthKind.NONE;
        return switch (raw.trim().toUpperCase()) {
            case "NONE" -> HttpAuthResolver.ServiceAuthKind.NONE;
            case "BEARER" -> HttpAuthResolver.ServiceAuthKind.BEARER;
            case "SIGNED_JWT" -> HttpAuthResolver.ServiceAuthKind.SIGNED_JWT;
            default -> throw new IllegalArgumentException(
                    "unknown service_auth_kind '" + raw + "'");
        };
    }
}
