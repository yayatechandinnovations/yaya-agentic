package com.yayatechandinnovations.yayaagentic.channel.whatsapp;

import com.yayatechandinnovations.yayaagentic.persistence.ChannelAutoReplyRepository;
import com.yayatechandinnovations.yayaagentic.persistence.InboundMessageRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The third housekeeping chore: auto-reply claims (ticket-share design §8 P4) are reaped after 30 days. */
class InboundHousekeepingJobTest {

    @Test
    void reaps_auto_reply_claims_older_than_thirty_days_alongside_the_inbound_chores() {
        InboundMessageRepository inbound = mock(InboundMessageRepository.class);
        ChannelAutoReplyRepository autoReplies = mock(ChannelAutoReplyRepository.class);
        when(inbound.findTop100ByProcessedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(any())).thenReturn(List.of());
        when(inbound.findTop100ByReceivedAtBeforeOrderByReceivedAtAsc(any())).thenReturn(List.of());
        when(autoReplies.deleteByRepliedAtBefore(any())).thenReturn(3);
        InboundHousekeepingJob job = new InboundHousekeepingJob(inbound, autoReplies, mock(InboundDispatchWorker.class),
                true, 5, 24);

        job.runHousekeeping();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(autoReplies).deleteByRepliedAtBefore(cutoff.capture());
        OffsetDateTime expected = OffsetDateTime.now(ZoneOffset.UTC)
                .minusDays(InboundHousekeepingJob.AUTO_REPLY_RETENTION_DAYS);
        assertThat(Duration.between(cutoff.getValue(), expected).abs()).isLessThan(Duration.ofMinutes(1));
    }

    @Test
    void disabled_job_touches_nothing() {
        ChannelAutoReplyRepository autoReplies = mock(ChannelAutoReplyRepository.class);
        new InboundHousekeepingJob(mock(InboundMessageRepository.class), autoReplies,
                mock(InboundDispatchWorker.class), false, 5, 24).runHousekeeping();
        verify(autoReplies, org.mockito.Mockito.never()).deleteByRepliedAtBefore(any());
    }
}
