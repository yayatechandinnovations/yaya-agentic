package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WhatsAppSenderAssignmentEventRepository
        extends JpaRepository<WhatsAppSenderAssignmentEventEntity, Long> {

    @WorkspaceUnscoped("platform-admin audit of a platform sender spans its workspaces")
    List<WhatsAppSenderAssignmentEventEntity> findBySenderIdOrderByOccurredAtDesc(String senderId);
}
