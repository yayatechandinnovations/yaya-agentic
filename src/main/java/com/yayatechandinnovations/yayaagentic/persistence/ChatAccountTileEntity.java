package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One server-side directory tile — a chat the account added
 * ({@code chat-account-self-service-design.md} §7.1). Keyed to the account so a
 * reinstalled or re-verified device recovers the list. The on-device store stays
 * the fast path; this is the recovery source.
 */
@Entity
@Table(name = "chat_account_tiles")
@IdClass(ChatAccountTileEntity.Key.class)
public class ChatAccountTileEntity {

    @Id
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Id
    @Column(name = "entry_point_id", nullable = false, length = 64)
    private String entryPointId;

    @Column(name = "handle", length = 64)
    private String handle;

    @Column(name = "added_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime addedAt;

    protected ChatAccountTileEntity() {}

    public ChatAccountTileEntity(UUID accountId, String entryPointId, String handle) {
        this.accountId = accountId;
        this.entryPointId = entryPointId;
        this.handle = handle;
    }

    public UUID getAccountId() { return accountId; }
    public String getEntryPointId() { return entryPointId; }
    public String getHandle() { return handle; }
    public OffsetDateTime getAddedAt() { return addedAt; }

    public void setHandle(String handle) { this.handle = handle; }

    public static class Key implements Serializable {
        private UUID accountId;
        private String entryPointId;

        public Key() {}
        public Key(UUID accountId, String entryPointId) {
            this.accountId = accountId;
            this.entryPointId = entryPointId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(accountId, key.accountId)
                    && Objects.equals(entryPointId, key.entryPointId);
        }

        @Override
        public int hashCode() { return Objects.hash(accountId, entryPointId); }
    }
}
