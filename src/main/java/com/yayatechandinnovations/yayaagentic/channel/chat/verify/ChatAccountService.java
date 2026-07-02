package com.yayatechandinnovations.yayaagentic.channel.chat.verify;

import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ChatAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for phone-verified chat accounts ({@code chat-channel-design.md} §20).
 * Lets the verify flow ask a returning phone for nothing but its OTP, while still
 * collecting a name the first time a phone registers.
 */
@Service
public class ChatAccountService {

    private final ChatAccountRepository accounts;

    public ChatAccountService(ChatAccountRepository accounts) {
        this.accounts = accounts;
    }

    /** Does a completed registration already exist for this phone? Drives whether
     *  the app asks for a name before sending the OTP. */
    @Transactional(readOnly = true)
    public boolean isRegistered(String phoneE164) {
        return accounts.existsById(phoneE164);
    }

    /**
     * Records a successful verification and returns the effective name to stamp on
     * the device pass. First-time registration stores the supplied name; a returning
     * phone keeps its stored name unless a new non-blank one is supplied. Called only
     * after Twilio has approved the code.
     */
    @Transactional
    public String completeVerification(String phoneE164, String suppliedName) {
        String clean = suppliedName == null || suppliedName.isBlank() ? null : suppliedName.trim();
        ChatAccountEntity account = accounts.findById(phoneE164).orElse(null);
        if (account == null) {
            accounts.save(new ChatAccountEntity(phoneE164, clean));
            return clean;
        }
        if (clean != null && !clean.equals(account.getName())) {
            account.setName(clean);
            accounts.save(account);
        }
        return account.getName();
    }
}
