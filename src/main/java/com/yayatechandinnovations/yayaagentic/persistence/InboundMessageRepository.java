package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;

public interface InboundMessageRepository extends JpaRepository<InboundMessageEntity, String> {

    /** Rescue job: replay rows that never landed processed_at. */
    @WorkspaceUnscoped("rescue job sweeps unprocessed inbound across workspaces")
    List<InboundMessageEntity> findTop100ByProcessedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(
            OffsetDateTime cutoff);

    /** Operator-tier: stuck rows for a single workspace. */
    List<InboundMessageEntity> findByTenantIdAndProcessedAtIsNullOrderByReceivedAtAsc(
            String tenantId);

    /** Reaper: rows past the §16 24h dedup window. Cross-tenant by design. */
    @WorkspaceUnscoped("housekeeping reaper sweeps stale rows across workspaces")
    List<InboundMessageEntity> findTop100ByReceivedAtBeforeOrderByReceivedAtAsc(
            OffsetDateTime cutoff);

    /** Service-window check (§10.2): is there an inbound from this end user
     *  within the last 24h? The sender-id is the tenant scope here (a sender
     *  belongs to exactly one tenant); we don't take tenantId to keep the
     *  hot path one index seek instead of two. */
    @WorkspaceUnscoped("service-window check; sender_id IS the tenant scope")
    java.util.Optional<InboundMessageEntity>
        findFirstBySenderIdAndEndUserPhoneAndReceivedAtAfterOrderByReceivedAtDesc(
                String senderId, String endUserPhone, OffsetDateTime cutoff);

    /** Operator takeover (§11.3): what the customer wrote while a person was handling the conversation. */
    List<InboundMessageEntity> findTop50ByTenantIdAndEndUserPhoneAndReceivedAtAfterOrderByReceivedAtAsc(
            String tenantId, String endUserPhone, OffsetDateTime since);

    /** The router's "last inbound before this one" (whatsapp-shared-sender-design.md §7.2). */
    @WorkspaceUnscoped("on a platform sender the phone's inbound spans workspaces; only timestamps are read")
    java.util.Optional<InboundMessageEntity>
        findFirstBySenderIdAndEndUserPhoneAndReceivedAtBeforeOrderByReceivedAtDesc(
                String senderId, String endUserPhone, OffsetDateTime before);

    /** Inbound on a platform sender that no workspace took (§12 «Unrouted»). */
    @WorkspaceUnscoped("platform-admin view of a platform sender's unrouted inbound")
    List<InboundMessageEntity> findTop200BySenderIdAndTenantIdIsNullOrderByReceivedAtDesc(String senderId);

    /** Wrapper to give the dispatcher a tidy method name. */
    @WorkspaceUnscoped("delegates to findFirst… above")
    default java.util.Optional<InboundMessageEntity> findLatestForSenderAndEndUserSince(
            String senderId, String endUserPhone, OffsetDateTime cutoff) {
        return findFirstBySenderIdAndEndUserPhoneAndReceivedAtAfterOrderByReceivedAtDesc(
                senderId, endUserPhone, cutoff);
    }
}
