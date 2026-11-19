package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface ChannelAutoReplyRepository
        extends JpaRepository<ChannelAutoReplyEntity, ChannelAutoReplyEntity.PK> {

    /** (sender_id, end_user_phone) is sender-scoped, hence tenant-scoped: a sender belongs to one tenant. */
    @WorkspaceUnscoped("sender_id IS the tenant scope")
    Optional<ChannelAutoReplyEntity> findBySenderIdAndEndUserPhone(String senderId, String endUserPhone);

    /**
     * Record "replied now" for the (sender, phone) unless a reply newer than
     * {@code cutoff} already stands. One statement, so two workers racing on
     * the same caller cannot both claim (the loser sees 0 rows, never a
     * duplicate-key error that would poison its transaction) and the 24 h
     * boundary is decided by the database, not by a read-then-write.
     *
     * @return 1 when this call claimed the reply, 0 when a fresh one stands
     */
    @WorkspaceUnscoped("sender_id IS the tenant scope")
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO channel_auto_replies (sender_id, end_user_phone, replied_at)
            VALUES (:senderId, :endUserPhone, :now)
            ON CONFLICT (sender_id, end_user_phone) DO UPDATE SET replied_at = EXCLUDED.replied_at
            WHERE channel_auto_replies.replied_at < :cutoff
            """, nativeQuery = true)
    int claim(@Param("senderId") String senderId,
              @Param("endUserPhone") String endUserPhone,
              @Param("now") OffsetDateTime now,
              @Param("cutoff") OffsetDateTime cutoff);

    /** Claims older than {@code cutoff} are long past any 24 h window; the housekeeping job reaps them. */
    @WorkspaceUnscoped("platform-wide housekeeping")
    @Transactional
    @Modifying
    @Query("DELETE FROM ChannelAutoReplyEntity e WHERE e.repliedAt < :cutoff")
    int deleteByRepliedAtBefore(@Param("cutoff") OffsetDateTime cutoff);
}
