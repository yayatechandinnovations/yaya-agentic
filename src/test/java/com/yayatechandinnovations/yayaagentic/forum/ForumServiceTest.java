package com.yayatechandinnovations.yayaagentic.forum;

import com.yayatechandinnovations.yayaagentic.persistence.ForumPostEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ForumPostRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ForumReactionRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ForumReportRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ForumSettingsRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ForumReportEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Forum rules ({@code chat-experience-design.md} §8): body limits, single-level
 * threading, the fixed reaction set, and report auto-hide at the threshold.
 */
class ForumServiceTest {

    private final ForumPostRepository posts = mock(ForumPostRepository.class);
    private final ForumReactionRepository reactions = mock(ForumReactionRepository.class);
    private final ForumReportRepository reports = mock(ForumReportRepository.class);
    private final ForumSettingsRepository settings = mock(ForumSettingsRepository.class);
    private final ForumService svc = new ForumService(posts, reactions, reports, settings);

    @BeforeEach
    void saves() {
        when(posts.save(any())).thenAnswer(i -> i.getArgument(0));
        when(reports.save(any())).thenAnswer(i -> i.getArgument(0));
        when(reactions.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void postValidatesBodyLimits() {
        assertThatThrownBy(() -> svc.postAsUser("t1", "ep1", UUID.randomUUID(), "Ada", " ", null))
                .isInstanceOf(ForumService.ForumException.class)
                .hasFieldOrPropertyWithValue("code", "empty_body");
        assertThatThrownBy(() -> svc.postAsUser("t1", "ep1", UUID.randomUUID(), "Ada",
                "x".repeat(2001), null))
                .hasFieldOrPropertyWithValue("code", "too_long");
        assertThatThrownBy(() -> svc.postAsUser("t1", "ep1", UUID.randomUUID(), "Ada",
                "a http://1 b http://2 c http://3 d http://4", null))
                .hasFieldOrPropertyWithValue("code", "too_many_links");
    }

    @Test
    void replyToAReplyFlattensToTheTopLevelParent() {
        ForumPostEntity top = new ForumPostEntity("fp_top", "t1", "ep1",
                ForumPostEntity.AUTHOR_USER, UUID.randomUUID(), null, "Ada", "hello", null);
        ForumPostEntity reply = new ForumPostEntity("fp_reply", "t1", "ep1",
                ForumPostEntity.AUTHOR_USER, UUID.randomUUID(), null, "Bob", "hi", "fp_top");
        when(posts.findById("fp_top")).thenReturn(Optional.of(top));
        when(posts.findById("fp_reply")).thenReturn(Optional.of(reply));

        ForumPostEntity created = svc.postAsUser("t1", "ep1", UUID.randomUUID(),
                "Cyd", "me too", "fp_reply");
        assertThat(created.getReplyTo()).isEqualTo("fp_top");
        assertThat(top.getReplyCount()).isEqualTo(1);
    }

    @Test
    void onlyTheFixedReactionSetIsAccepted() {
        ForumPostEntity p = visiblePost("fp_1");
        when(posts.findById("fp_1")).thenReturn(Optional.of(p));
        when(reactions.existsById(any())).thenReturn(false);

        svc.react("ep1", "fp_1", UUID.randomUUID(), "👍");
        assertThat(p.getReactionCounts()).containsEntry("👍", 1);

        assertThatThrownBy(() -> svc.react("ep1", "fp_1", UUID.randomUUID(), "🖕"))
                .hasFieldOrPropertyWithValue("code", "bad_reaction");
    }

    @Test
    void reactionsAreIdempotentPerAccount() {
        ForumPostEntity p = visiblePost("fp_1");
        when(posts.findById("fp_1")).thenReturn(Optional.of(p));
        when(reactions.existsById(any())).thenReturn(true);
        svc.react("ep1", "fp_1", UUID.randomUUID(), "👍");
        assertThat(p.getReactionCounts()).doesNotContainKey("👍");
    }

    @Test
    void fifthUnresolvedReportAutoHides() {
        ForumPostEntity p = visiblePost("fp_1");
        when(posts.findById("fp_1")).thenReturn(Optional.of(p));
        when(reports.findByPostIdAndAccountIdAndResolvedAtIsNull(any(), any()))
                .thenReturn(Optional.empty());
        when(reports.countByPostIdAndResolvedAtIsNull("fp_1")).thenReturn(5L);

        svc.report("ep1", "fp_1", UUID.randomUUID(), "spam");
        assertThat(p.isHidden()).isTrue();
        assertThat(p.getReportCount()).isEqualTo(1);
    }

    @Test
    void duplicateReportFromSameAccountIsIgnored() {
        ForumPostEntity p = visiblePost("fp_1");
        UUID account = UUID.randomUUID();
        when(posts.findById("fp_1")).thenReturn(Optional.of(p));
        when(reports.findByPostIdAndAccountIdAndResolvedAtIsNull("fp_1", account))
                .thenReturn(Optional.of(new ForumReportEntity("fr_1", "fp_1", account, null)));

        svc.report("ep1", "fp_1", account, "again");
        assertThat(p.getReportCount()).isZero();
    }

    @Test
    void moderatorUnhideDismissesOpenReports() {
        ForumPostEntity p = visiblePost("fp_1");
        p.setHidden(true);
        ForumReportEntity open = new ForumReportEntity("fr_1", "fp_1", UUID.randomUUID(), "spam");
        when(posts.findById("fp_1")).thenReturn(Optional.of(p));
        when(reports.findByPostIdAndResolvedAtIsNull("fp_1")).thenReturn(List.of(open));

        svc.moderate("t1", "fp_1", false, null, false);
        assertThat(p.isHidden()).isFalse();
        assertThat(open.getResolution()).isEqualTo(ForumReportEntity.RESOLUTION_DISMISSED);
    }

    @Test
    void crossTenantModerationIsInvisible() {
        ForumPostEntity p = visiblePost("fp_1");
        when(posts.findById("fp_1")).thenReturn(Optional.of(p));
        assertThat(svc.moderate("OTHER_TENANT", "fp_1", true, null, false)).isEmpty();
    }

    @Test
    void editOnlyWithinWindowAndOnlyOwn() {
        UUID owner = UUID.randomUUID();
        ForumPostEntity p = new ForumPostEntity("fp_1", "t1", "ep1",
                ForumPostEntity.AUTHOR_USER, owner, null, "Ada", "hello", null);
        when(posts.findById("fp_1")).thenReturn(Optional.of(p));

        // createdAt is null in a fresh mock row → treated as within the window.
        svc.editOwn("fp_1", owner, "hello edited");
        assertThat(p.getBody()).isEqualTo("hello edited");
        assertThat(p.getEditedAt()).isNotNull();

        assertThatThrownBy(() -> svc.editOwn("fp_1", UUID.randomUUID(), "hijack"))
                .hasFieldOrPropertyWithValue("code", "not_found");
    }

    private static ForumPostEntity visiblePost(String id) {
        return new ForumPostEntity(id, "t1", "ep1",
                ForumPostEntity.AUTHOR_USER, UUID.randomUUID(), null, "Ada", "hello", null);
    }
}
