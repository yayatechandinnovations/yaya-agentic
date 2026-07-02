package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Chat accounts (V60). Rows are keyed by E.164 phone and are not workspace-scoped —
 * the device pass they back is platform-signed and crosses tenants (chat-channel-design.md §20).
 */
@WorkspaceUnscoped("chat accounts are phone-keyed and tenant-agnostic, like the device pass they back")
public interface ChatAccountRepository extends JpaRepository<ChatAccountEntity, String> {
}
