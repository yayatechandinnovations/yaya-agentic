package com.yayatechandinnovations.yayaagentic.channel.chat.receipts;

import com.yayatechandinnovations.yayaagentic.core.Ids;
import com.yayatechandinnovations.yayaagentic.engine.TurnEvent;
import com.yayatechandinnovations.yayaagentic.persistence.ChatReceiptEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatReceiptRepository;
import com.yayatechandinnovations.yayaagentic.persistence.ReceiptTemplateEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ReceiptTemplateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wallet capture rules ({@code chat-experience-design.md} §7.5): only
 * {@code receipt} hints persist, only for account-linked sessions, and the
 * template layout is snapshotted at issuance.
 */
class ChatReceiptServiceTest {

    private final ChatReceiptRepository repo = mock(ChatReceiptRepository.class);
    private final ReceiptTemplateRepository templateRepo = mock(ReceiptTemplateRepository.class);
    private final ChatReceiptService svc =
            new ChatReceiptService(repo, new ReceiptTemplateService(templateRepo));
    private final Ids.TenantId tenant = new Ids.TenantId("t1");

    private static TurnEvent receiptHint(String templateId) {
        return new TurnEvent.UiHint("receipt", Map.of(
                "templateId", templateId,
                "data", Map.of("serial", "A-001", "totalAmount", "150.00")));
    }

    @Test
    void receiptHintOnVerifiedSessionPersistsWithSnapshot() {
        UUID account = UUID.randomUUID();
        ReceiptTemplateEntity template = new ReceiptTemplateEntity("rt_1", "t1", "Ticket",
                null, 32, "58mm", List.of(Map.of("type", "header")), null);
        when(templateRepo.findByTenantIdAndId("t1", "rt_1")).thenReturn(Optional.of(template));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.maybePersist(receiptHint("rt_1"), tenant, "s1", "e_h", account);

        ArgumentCaptor<ChatReceiptEntity> captor = ArgumentCaptor.forClass(ChatReceiptEntity.class);
        verify(repo).save(captor.capture());
        ChatReceiptEntity saved = captor.getValue();
        assertThat(saved.getAccountId()).isEqualTo(account);
        assertThat(saved.getTemplateId()).isEqualTo("rt_1");
        assertThat(saved.getTemplateSnapshot()).containsEntry("id", "rt_1");
        assertThat(saved.getData()).containsEntry("serial", "A-001");
    }

    @Test
    void anonymousSessionIsNotCollected() {
        svc.maybePersist(receiptHint("rt_1"), tenant, "s1", "e_h", null);
        verify(repo, never()).save(any());
    }

    @Test
    void nonReceiptHintsAreIgnored() {
        svc.maybePersist(new TurnEvent.UiHint("cart", Map.of("data", Map.of())),
                tenant, "s1", "e_h", UUID.randomUUID());
        svc.maybePersist(new TurnEvent.Token("hello"), tenant, "s1", "e_h", UUID.randomUUID());
        verify(repo, never()).save(any());
    }

    @Test
    void unknownTemplateStillPersistsWithFallbackShell() {
        when(templateRepo.findByTenantIdAndId("t1", "rt_ghost")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.maybePersist(receiptHint("rt_ghost"), tenant, "s1", "e_h", UUID.randomUUID());

        ArgumentCaptor<ChatReceiptEntity> captor = ArgumentCaptor.forClass(ChatReceiptEntity.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getTemplateSnapshot()).containsEntry("id", "rt_ghost");
    }

    @Test
    void draftReceiptsAreRenderedButNeverCollected() {
        // A ticket in progress (§7.8): rendered live, never in the wallet.
        TurnEvent draft = new TurnEvent.UiHint("receipt", Map.of(
                "templateId", "rt_1", "draft", true,
                "data", Map.of("serial", "DRAFT")));
        svc.maybePersist(draft, tenant, "s1", "e_h", UUID.randomUUID());
        verify(repo, never()).save(any());
    }

    @Test
    void hintWithoutDataIsIgnored() {
        svc.maybePersist(new TurnEvent.UiHint("receipt", Map.of("templateId", "rt_1")),
                tenant, "s1", "e_h", UUID.randomUUID());
        verify(repo, never()).save(any());
    }

    @Test
    void walletFailureNeverPropagates() {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        when(templateRepo.findByTenantIdAndId(any(), any())).thenReturn(Optional.empty());
        // Must not throw — a wallet write failure can't break the live stream.
        svc.maybePersist(receiptHint("rt_1"), tenant, "s1", "e_h", UUID.randomUUID());
    }
}
