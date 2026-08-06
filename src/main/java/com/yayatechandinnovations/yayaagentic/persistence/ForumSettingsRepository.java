package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

/** Per-entry-point forum switch ({@code chat-experience-design.md} §8.1). */
@WorkspaceUnscoped("read on the public surface; written via tenant-guarded admin")
public interface ForumSettingsRepository extends JpaRepository<ForumSettingsEntity, String> {}
