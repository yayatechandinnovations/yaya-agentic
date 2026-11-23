package com.yayatechandinnovations.yayaagentic.forum;

import com.yayatechandinnovations.yayaagentic.persistence.ForumPostEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ForumPostRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ForumReportEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ForumReportRepository;
import com.yayatechandinnovations.yayaagentic.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The admin reports list is per tenant: reports carry no tenant, their posts do. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "yaya.agentic.llm.provider=stub",
        "spring.ai.anthropic.api-key="
})
class ForumReportsTenantScopeTest {

    @Autowired ForumPostRepository posts;
    @Autowired ForumReportRepository reports;
    @Autowired ForumService forum;

    @Test
    void open_reports_are_only_those_on_the_tenants_own_posts() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var mine = posts.save(new ForumPostEntity("fp_m_" + suffix, "scope-a-" + suffix, "ep1",
                ForumPostEntity.AUTHOR_USER, UUID.randomUUID(), null, "Ada", "hello", null));
        var theirs = posts.save(new ForumPostEntity("fp_t_" + suffix, "scope-b-" + suffix, "ep2",
                ForumPostEntity.AUTHOR_USER, UUID.randomUUID(), null, "Bob", "hi", null));
        reports.save(new ForumReportEntity("fr_m_" + suffix, mine.getId(), UUID.randomUUID(), "spam"));
        reports.save(new ForumReportEntity("fr_t_" + suffix, theirs.getId(), UUID.randomUUID(), "spam"));

        assertThat(forum.openReports("scope-a-" + suffix))
                .extracting(ForumReportEntity::getPostId)
                .containsExactly(mine.getId());
        assertThat(forum.openReports("scope-b-" + suffix))
                .extracting(ForumReportEntity::getPostId)
                .containsExactly(theirs.getId());
    }
}
