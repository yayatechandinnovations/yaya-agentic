package com.yayatechandinnovations.yayaagentic.channel.chat;

import com.yayatechandinnovations.yayaagentic.persistence.SessionEntity;
import com.yayatechandinnovations.yayaagentic.persistence.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Links a chat session to its L1 account for resume
 * ({@code chat-account-self-service-design.md} §5.3, §7.2). Deliberately narrow:
 * it only annotates two nullable index columns and reads them back. The
 * {@code ConversationRecorder} still owns the session row's lifecycle (design
 * §5.9) — this never creates, ends, or deletes a session.
 */
@Service
public class ChatSessionLinkService {

    private final SessionRepository sessions;

    public ChatSessionLinkService(SessionRepository sessions) {
        this.sessions = sessions;
    }

    /** Stamp account + entry point on a freshly-started session. Best-effort:
     *  a missing row (shouldn't happen) is a no-op, not an error. */
    @Transactional
    public void link(UUID sessionId, UUID accountId, String entryPointId) {
        if (sessionId == null || accountId == null) return;
        sessions.linkAccount(sessionId, accountId, entryPointId);
    }

    @Transactional(readOnly = true)
    public Optional<SessionEntity> latest(UUID accountId, String entryPointId) {
        if (accountId == null || entryPointId == null) return Optional.empty();
        return sessions.findFirstByChatAccountIdAndEntryPointIdOrderByCreatedAtDesc(accountId, entryPointId);
    }

    @Transactional(readOnly = true)
    public Optional<UUID> accountOf(UUID sessionId) {
        return sessions.findById(sessionId).map(SessionEntity::getChatAccountId);
    }

    @Transactional(readOnly = true)
    public List<SessionEntity> sessionsForAccount(UUID accountId) {
        return sessions.findByChatAccountId(accountId);
    }
}
