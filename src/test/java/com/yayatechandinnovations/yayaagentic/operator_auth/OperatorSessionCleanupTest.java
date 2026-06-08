package com.yayatechandinnovations.yayaagentic.operator_auth;

import com.yayatechandinnovations.yayaagentic.operator_auth.session.OperatorSessionCleanup;
import com.yayatechandinnovations.yayaagentic.persistence.OperatorSessionEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OperatorSessionRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the scheduled cleanup deletes expired rows.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class OperatorSessionCleanupTest {

    @Autowired OperatorSessionCleanup cleanup;
    @Autowired OperatorSessionRepository repo;

    @Test
    void purges_expired_rows_only() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String expiredId = "expired-test-" + System.nanoTime();
        String liveId = "live-test-" + System.nanoTime();

        repo.save(new OperatorSessionEntity(
                expiredId, "alice", "alice", "BOOTSTRAP",
                now.minusHours(2), now.minusHours(1),     // expired 1h ago
                "127.0.0.1", "test", null));
        repo.save(new OperatorSessionEntity(
                liveId, "bob", "bob", "BOOTSTRAP",
                now, now.plusHours(1),                    // good for another hour
                "127.0.0.1", "test", null));

        cleanup.purgeExpired();

        assertThat(repo.findById(expiredId)).as("expired row should be gone").isEmpty();
        assertThat(repo.findById(liveId)).as("live row should survive").isPresent();
    }
}
