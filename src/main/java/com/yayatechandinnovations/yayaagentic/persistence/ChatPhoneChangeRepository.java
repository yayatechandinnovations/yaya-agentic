package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** In-flight phone-change ceremonies ({@code chat-account-self-service-design.md} §6.2). */
@WorkspaceUnscoped("phone changes belong to platform-verified chat accounts, which are tenant-agnostic")
public interface ChatPhoneChangeRepository extends JpaRepository<ChatPhoneChangeEntity, UUID> {

    List<ChatPhoneChangeEntity> findByAccountIdAndStateIn(UUID accountId, List<String> states);
}
