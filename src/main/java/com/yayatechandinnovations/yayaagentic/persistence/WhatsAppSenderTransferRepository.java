package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WhatsAppSenderTransferRepository extends JpaRepository<WhatsAppSenderTransferEntity, String> {

    /** The re-point retry sweep (whatsapp-sender-transfer-design.md §6.4). */
    @WorkspaceUnscoped("platform job retries webhook re-points across workspaces")
    List<WhatsAppSenderTransferEntity> findByRepointStatusOrderByCreatedAtAsc(String repointStatus);

    /** The transfer that created a destination sender, read by a platform admin's retry. */
    @WorkspaceUnscoped("a transfer spans two workspaces; read by platform admins")
    Optional<WhatsAppSenderTransferEntity> findFirstByDestSenderIdOrderByCreatedAtDesc(String destSenderId);
}
