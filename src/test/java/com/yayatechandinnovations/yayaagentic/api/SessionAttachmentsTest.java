package com.yayatechandinnovations.yayaagentic.api;

import com.yayatechandinnovations.yayaagentic.channel.chat.media.ChatAttachmentService;
import com.yayatechandinnovations.yayaagentic.channel.chat.media.ChatMessageComposer;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAttachmentEntity;
import com.yayatechandinnovations.yayaagentic.testsupport.TestMedia;
import com.yayatechandinnovations.yayaagentic.usage.UsageMeter;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code images_in} is metered when the turn ends — a turn refused before the model ran shows no image. */
class SessionAttachmentsTest {

    private final ChatAttachmentService service = mock(ChatAttachmentService.class);
    private final UsageMeter usage = mock(UsageMeter.class);
    private final SessionAttachments attachments = new SessionAttachments(service, usage);
    private final Ids.TenantId tenant = new Ids.TenantId("t1");

    private SessionAttachments.Composed composeOnePhoto() {
        ChatAttachmentService.ChatAttachment photo = new ChatAttachmentService.ChatAttachment("chatatt_1",
                ChatAttachmentEntity.KIND_IMAGE, "image/png", "ticket.png", 1_000, null, null, null, List.of(), null);
        when(service.resolveForSend(eq(tenant), eq("s-1"), any())).thenReturn(List.of(photo));
        when(service.readBytes(eq(tenant), eq("chatatt_1"))).thenReturn(Optional.of(TestMedia.png(16, 16)));
        return attachments.compose(tenant, "s-1", "p", "mira", Map.of(), List.of("chatatt_1"));
    }

    @Test
    void composing_counts_the_pictures_but_meters_nothing_yet() {
        SessionAttachments.Composed composed = composeOnePhoto();

        assertThat(composed.images()).isEqualTo(1);
        assertThat(composed.ids()).containsExactly("chatatt_1");
        assertThat(composed.message().attachments()).containsKey(ChatMessageComposer.IMAGES_KEY);
        verify(usage, never()).recordImagesIn(anyString(), any(), anyLong());
    }

    @Test
    void the_end_of_the_turn_meters_the_pictures_and_marks_the_rows() {
        SessionAttachments.Composed composed = composeOnePhoto();
        Flux<TurnEvent> turn = Flux.just(new TurnEvent.Token("ok"), new TurnEvent.End(new Ids.TurnId("turn-9"), 1, 1));

        attachments.markOnEnd(turn, composed, tenant, "p").blockLast();

        verify(usage).recordImagesIn("t1", "p", 1);
        verify(service).markReferenced(List.of("chatatt_1"), "turn-9");
    }

    @Test
    void a_turn_refused_or_failed_before_it_ends_meters_no_picture() {
        SessionAttachments.Composed composed = composeOnePhoto();
        Flux<TurnEvent> refused = Flux.error(new ChatApiException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "quota_exceeded", "monthly messages used up", 60));

        assertThatThrownBy(() -> attachments.markOnEnd(refused, composed, tenant, "p").blockLast())
                .isInstanceOf(ChatApiException.class);

        verify(usage, never()).recordImagesIn(anyString(), any(), anyLong());
        verify(service, never()).markReferenced(any(), anyString());
    }
}
