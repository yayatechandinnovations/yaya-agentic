package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

import com.yayatechandinnovations.yayaagentic.api.ChatApiException;
import com.yayatechandinnovations.yayaagentic.channel.chat.ChatRateLimiter;
import com.yayatechandinnovations.yayaagentic.persistence.ChatPhoneChangeEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatPhoneChangeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in the dual-OTP phone-change ceremony ({@code chat-account-self-service-design.md}
 * §6.2): the CURRENT number must verify before the NEW number is ever texted, and
 * the account migrates only when both proofs succeed.
 */
class ChatPhoneChangeServiceTest {

    private final PhoneVerifier verifier = mock(PhoneVerifier.class);
    private final ChatRateLimiter rateLimiter = mock(ChatRateLimiter.class);
    private final ChatAccountService accounts = mock(ChatAccountService.class);
    private final ChatPhoneChangeRepository changes = mock(ChatPhoneChangeRepository.class);
    private final ChatPhoneChangeService svc =
            new ChatPhoneChangeService(verifier, rateLimiter, accounts, changes);

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final String OLD = "+18095551234";
    private static final String NEW = "+18095559999";

    @BeforeEach
    void allowAllRateLimits() {
        when(rateLimiter.hit(anyString(), anyString(), anyInt(), any()))
                .thenReturn(new ChatRateLimiter.Decision(true, 0));
        when(changes.save(any(ChatPhoneChangeEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(changes.findByAccountIdAndStateIn(any(), any())).thenReturn(List.of());
    }

    @Test
    void startTextsTheOldNumberNotTheNew() {
        when(accounts.isRegistered(NEW)).thenReturn(false);
        when(verifier.start(OLD)).thenReturn(PhoneVerifier.StartResult.pending());

        ChatPhoneChangeEntity c = svc.start(ACCOUNT, OLD, NEW, "1.2.3.4");

        assertThat(c.getState()).isEqualTo(ChatPhoneChangeEntity.OLD_PENDING);
        verify(verifier).start(OLD);
        verify(verifier, never()).start(NEW);
    }

    @Test
    void startRejectsANumberAlreadyOwned() {
        when(accounts.isRegistered(NEW)).thenReturn(true);
        assertThatThrownBy(() -> svc.start(ACCOUNT, OLD, NEW, "1.2.3.4"))
                .isInstanceOf(ChatApiException.class);
        verify(verifier, never()).start(anyString());
    }

    @Test
    void verifyOldTextsTheNewNumberOnlyAfterTheOldCodePasses() {
        ChatPhoneChangeEntity c = oldPending();
        when(changes.findById(c.getId())).thenReturn(Optional.of(c));
        when(verifier.check(eq(OLD), eq("111111"))).thenReturn(new PhoneVerifier.CheckResult(true, null));
        when(verifier.start(NEW)).thenReturn(PhoneVerifier.StartResult.pending());

        svc.verifyOld(ACCOUNT, c.getId(), "111111", "1.2.3.4");

        assertThat(c.getState()).isEqualTo(ChatPhoneChangeEntity.NEW_PENDING);
        verify(verifier).start(NEW);
    }

    @Test
    void wrongOldCodeNeverTextsTheNewNumber() {
        ChatPhoneChangeEntity c = oldPending();
        when(changes.findById(c.getId())).thenReturn(Optional.of(c));
        when(verifier.check(eq(OLD), anyString())).thenReturn(new PhoneVerifier.CheckResult(false, "bad"));

        assertThatThrownBy(() -> svc.verifyOld(ACCOUNT, c.getId(), "000000", "1.2.3.4"))
                .isInstanceOf(ChatApiException.class);
        verify(verifier, never()).start(NEW);
        assertThat(c.getState()).isEqualTo(ChatPhoneChangeEntity.OLD_PENDING);
    }

    @Test
    void verifyNewMigratesTheAccount() {
        ChatPhoneChangeEntity c = oldPending();
        c.setState(ChatPhoneChangeEntity.NEW_PENDING);
        when(changes.findById(c.getId())).thenReturn(Optional.of(c));
        when(verifier.check(eq(NEW), eq("222222"))).thenReturn(new PhoneVerifier.CheckResult(true, null));
        when(accounts.isRegistered(NEW)).thenReturn(false);
        when(accounts.changePhone(ACCOUNT, NEW))
                .thenReturn(new ChatAccountService.VerifiedAccount(ACCOUNT.toString(), NEW, "Ada", 2));

        ChatAccountService.VerifiedAccount out = svc.verifyNew(ACCOUNT, c.getId(), "222222");

        assertThat(out.phoneE164()).isEqualTo(NEW);
        assertThat(c.getState()).isEqualTo(ChatPhoneChangeEntity.COMPLETE);
        verify(accounts).changePhone(ACCOUNT, NEW);
    }

    @Test
    void crossAccountChangeIsHiddenAsNotFound() {
        ChatPhoneChangeEntity c = oldPending(); // owned by ACCOUNT
        when(changes.findById(c.getId())).thenReturn(Optional.of(c));
        UUID otherAccount = UUID.randomUUID();
        assertThatThrownBy(() -> svc.verifyOld(otherAccount, c.getId(), "111111", "1.2.3.4"))
                .isInstanceOf(ChatApiException.class);
        verify(verifier, never()).check(anyString(), anyString());
    }

    private static ChatPhoneChangeEntity oldPending() {
        return new ChatPhoneChangeEntity(UUID.randomUUID(), ACCOUNT, OLD, NEW,
                java.time.OffsetDateTime.now().plusMinutes(15));
    }
}
