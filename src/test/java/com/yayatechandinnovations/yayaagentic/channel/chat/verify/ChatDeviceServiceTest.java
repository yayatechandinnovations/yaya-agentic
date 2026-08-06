package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountDeviceEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountDeviceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Device enrollment + secret resolution ({@code chat-experience-design.md} §5.4).
 * The plaintext secret leaves the server exactly once; only its SHA-256 is
 * stored, and pin-unlock resolves the device by constant-time hash match.
 */
class ChatDeviceServiceTest {

    private final ChatAccountDeviceRepository repo = mock(ChatAccountDeviceRepository.class);
    private final ChatDeviceService svc = new ChatDeviceService(repo);

    @Test
    void enrollStoresHashNotSecret() {
        UUID acct = UUID.randomUUID();
        when(repo.findByAccountIdAndDeviceId(acct, "dev1")).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        ChatDeviceService.Enrollment e = svc.enroll(acct, "dev1", "Chrome · macOS");

        ArgumentCaptor<ChatAccountDeviceEntity> captor = ArgumentCaptor.forClass(ChatAccountDeviceEntity.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getDeviceSecretHash())
                .isNotEqualTo(e.deviceSecret())
                .isEqualTo(ChatDeviceService.sha256Hex(e.deviceSecret()));
        assertThat(captor.getValue().isPinRequired()).isTrue();
    }

    @Test
    void reEnrollRotatesSecretClearsRevocationKeepsPolicy() {
        UUID acct = UUID.randomUUID();
        ChatAccountDeviceEntity existing = new ChatAccountDeviceEntity(acct, "dev1", "oldhash", "old label");
        existing.setPinRequired(false);
        existing.revoke();
        when(repo.findByAccountIdAndDeviceId(acct, "dev1")).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        ChatDeviceService.Enrollment e = svc.enroll(acct, "dev1", null);

        assertThat(existing.isRevoked()).isFalse();
        assertThat(existing.isPinRequired()).isFalse();
        assertThat(existing.getDeviceSecretHash()).isEqualTo(ChatDeviceService.sha256Hex(e.deviceSecret()));
        assertThat(e.pinRequired()).isFalse();
    }

    @Test
    void resolveBySecretMatchesOnlyTheRightRow() {
        UUID acct = UUID.randomUUID();
        ChatAccountDeviceEntity mine = new ChatAccountDeviceEntity(acct, "dev1", ChatDeviceService.sha256Hex("s3cret"), null);
        ChatAccountDeviceEntity other = new ChatAccountDeviceEntity(UUID.randomUUID(), "dev1", ChatDeviceService.sha256Hex("other"), null);
        when(repo.findByDeviceId("dev1")).thenReturn(List.of(other, mine));

        assertThat(svc.resolveBySecret("dev1", "s3cret")).hasValueSatisfying(d ->
                assertThat(d.getAccountId()).isEqualTo(acct));
        assertThat(svc.resolveBySecret("dev1", "wrong")).isEmpty();
        assertThat(svc.resolveBySecret("dev1", null)).isEmpty();
    }

    @Test
    void resolveBySecretSkipsRevokedRows() {
        UUID acct = UUID.randomUUID();
        ChatAccountDeviceEntity row = new ChatAccountDeviceEntity(acct, "dev1", ChatDeviceService.sha256Hex("s3cret"), null);
        row.revoke();
        when(repo.findByDeviceId("dev1")).thenReturn(List.of(row));

        assertThat(svc.resolveBySecret("dev1", "s3cret")).isEmpty();
    }

    @Test
    void revocationIsCachedButInvalidatedByRevoke() {
        UUID acct = UUID.randomUUID();
        ChatAccountDeviceEntity row = new ChatAccountDeviceEntity(acct, "dev1", "h", null);
        when(repo.findByAccountIdAndDeviceId(acct, "dev1")).thenReturn(Optional.of(row));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(svc.isRevoked(acct, "dev1")).isFalse();
        // Revoking through the service must not serve the stale cached "false".
        svc.revoke(acct, "dev1");
        assertThat(svc.isRevoked(acct, "dev1")).isTrue();
    }

    @Test
    void unknownDeviceIsNotRevoked() {
        UUID acct = UUID.randomUUID();
        when(repo.findByAccountIdAndDeviceId(acct, "ghost")).thenReturn(Optional.empty());
        assertThat(svc.isRevoked(acct, "ghost")).isFalse();
    }
}
