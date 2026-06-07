package com.yayatechandinnovations.yayaagentic.support;

import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Boot reads a pgvector Postgres + a plain Redis container from this
 * configuration and binds them to the application:
 * <ul>
 *   <li>{@link PostgreSQLContainer} → {@code DataSource} via
 *       {@link ServiceConnection}.</li>
 *   <li>Redis {@link GenericContainer} → a {@link RedisConnectionDetails}
 *       bean. Spring Boot's built-in ServiceConnection factories don't
 *       recognise a plain {@code GenericContainer} as Redis, and
 *       {@code DynamicPropertyRegistrar} only landed in Spring 6.2, so
 *       we provide the connection-details bean directly.</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> pgvectorContainer() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16")
                        .asCompatibleSubstituteFor("postgres"))
                .withReuse(true);
    }

    @Bean(destroyMethod = "stop")
    GenericContainer<?> redisContainer() {
        var c = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .withReuse(true);
        c.start();
        return c;
    }

    @Bean
    RedisConnectionDetails redisConnectionDetails(GenericContainer<?> redisContainer) {
        return new RedisConnectionDetails() {
            @Override
            public Standalone getStandalone() {
                return Standalone.of(
                        redisContainer.getHost(),
                        redisContainer.getMappedPort(6379));
            }
        };
    }
}
