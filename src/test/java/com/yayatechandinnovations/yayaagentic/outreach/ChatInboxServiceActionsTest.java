package com.yayatechandinnovations.yayaagentic.outreach;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.outreach.media.MediaTokenSigner;
import com.yayatechandinnovations.yayaagentic.persistence.ChatInboxEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatInboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §D3 drain round-trip: an inbox row's {@code actions_json} deserializes into
 * {@link ChatInboxService.InboxItem#actions()} so a proactive message surfaces
 * inline action buttons to the operator. A malformed blob never blocks delivery.
 */
class ChatInboxServiceActionsTest {

    private static final Ids.TenantId TENANT = new Ids.TenantId("acme");

    private final ChatInboxRepository repo = mock(ChatInboxRepository.class);
    private final MediaTokenSigner signer = mock(MediaTokenSigner.class);
    private final ChatInboxService service = new ChatInboxService(repo, signer, new ObjectMapper());

    private ChatInboxEntity row(String actionsJson) {
        ChatInboxEntity e = new ChatInboxEntity(TENANT.value(), "ep-1", "principal:op-1", "sess-1");
        ReflectionTestUtils.setField(e, "id", 1L);
        e.setBody("cash short by 200");
        e.setSenderDisplay("Cash Register #3");
        e.setActionsJson(actionsJson);
        return e;
    }

    @Test
    void deserializesActionsIntoInboxItem() {
        when(repo.findByTenantIdAndEntryPointIdAndRecipientKeyAndDeliveredAtIsNullOrderByCreatedAtAsc(
                any(), any(), any()))
                .thenReturn(List.of(row(
                        "[{\"label\":\"Confirmar\",\"send\":\"/confirm\",\"style\":\"primary\"}]")));

        List<ChatInboxService.InboxItem> items =
                service.drain(TENANT, "ep-1", "op-1", /* markDelivered */ false);

        assertThat(items).hasSize(1);
        List<ActionItem> actions = items.get(0).actions();
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).label()).isEqualTo("Confirmar");
        assertThat(actions.get(0).send()).isEqualTo("/confirm");
        assertThat(actions.get(0).style()).isEqualTo("primary");
    }

    @Test
    void malformedActionsBlobYieldsEmptyListNotFailure() {
        when(repo.findByTenantIdAndEntryPointIdAndRecipientKeyAndDeliveredAtIsNullOrderByCreatedAtAsc(
                any(), any(), any()))
                .thenReturn(List.of(row("{not-json")));

        List<ChatInboxService.InboxItem> items =
                service.drain(TENANT, "ep-1", "op-1", false);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).actions()).isEmpty();
        assertThat(items.get(0).body()).isEqualTo("cash short by 200");
    }
}
