package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks in the device-pass revocation rule ({@code chat-account-self-service-design.md}
 * §5.2): a signature-valid pass is honoured only while its generation matches the
 * account and the account is ACTIVE.
 */
class ChatAccountServiceTest {

    private final ChatAccountRepository repo = mock(ChatAccountRepository.class);
    private final ChatAccountService svc = new ChatAccountService(repo);

    private static ChatAccountEntity account(UUID id, String phone, int gen, boolean active) {
        ChatAccountEntity a = new ChatAccountEntity(id, phone, "Ada");
        for (int i = 1; i < gen; i++) a.bumpPassGen();
        if (!active) a.setStatus(ChatAccountEntity.STATUS_DELETED);
        return a;
    }

    @Test
    void passWithMatchingGenerationIsLive() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(account(id, "+18095551234", 3, true)));
        VerifiedDevice pass = new VerifiedDevice("+18095551234", "Ada", "dev", id.toString(), 3L);
        assertThat(svc.isLive(pass)).isTrue();
    }

    @Test
    void staleGenerationIsRevoked() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(account(id, "+18095551234", 4, true)));
        VerifiedDevice oldPass = new VerifiedDevice("+18095551234", "Ada", "dev", id.toString(), 3L);
        assertThat(svc.isLive(oldPass)).isFalse();
    }

    @Test
    void deletedAccountRevokesEvenMatchingGeneration() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(account(id, "+18095551234", 2, false)));
        VerifiedDevice pass = new VerifiedDevice("+18095551234", "Ada", "dev", id.toString(), 2L);
        assertThat(svc.isLive(pass)).isFalse();
    }

    @Test
    void legacyPassGrandfatheredWhilePhoneHasActiveAccount() {
        UUID id = UUID.randomUUID();
        when(repo.findActiveByPhone("+18095551234"))
                .thenReturn(Optional.of(account(id, "+18095551234", 1, true)));
        VerifiedDevice legacy = new VerifiedDevice("+18095551234", "Ada", "dev"); // no acct/gen
        assertThat(svc.isLive(legacy)).isTrue();
    }

    @Test
    void legacyPassRejectedOnceItsPhoneAccountIsGone() {
        when(repo.findActiveByPhone("+18095551234")).thenReturn(Optional.empty());
        VerifiedDevice legacy = new VerifiedDevice("+18095551234", "Ada", "dev");
        assertThat(svc.isLive(legacy)).isFalse();
    }

    @Test
    void changePhoneBumpsGenerationAndMovesNumber() {
        UUID id = UUID.randomUUID();
        ChatAccountEntity a = account(id, "+18095551234", 1, true);
        when(repo.findById(id)).thenReturn(Optional.of(a));
        when(repo.save(any(ChatAccountEntity.class))).thenAnswer(i -> i.getArgument(0));

        ChatAccountService.VerifiedAccount updated = svc.changePhone(id, "+18095559999");

        assertThat(updated.phoneE164()).isEqualTo("+18095559999");
        assertThat(updated.passGen()).isEqualTo(2);
    }
}
