package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChannelOptOutRepository
        extends JpaRepository<ChannelOptOutEntity, ChannelOptOutEntity.PK> {

    /** Outbound dispatcher consults this on every send. The (sender_id,
     *  end_user_phone) tuple is sender-scoped, which is also tenant-scoped
     *  since a sender belongs to one tenant. */
    @WorkspaceUnscoped("sender_id IS the tenant scope")
    Optional<ChannelOptOutEntity> findBySenderIdAndEndUserPhone(
            String senderId, String endUserPhone);

    List<ChannelOptOutEntity> findBySenderId(String senderId);
}
