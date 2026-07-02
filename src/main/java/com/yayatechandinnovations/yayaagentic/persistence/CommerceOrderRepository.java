package com.yayatechandinnovations.yayaagentic.persistence;

import com.yayatechandinnovations.yayaagentic.operator_auth.workspace.WorkspaceUnscoped;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Orders are written at checkout and read back by primary key or by the PSP
 * {@code order_ref} on an inbound webhook.
 */
public interface CommerceOrderRepository extends JpaRepository<CommerceOrderEntity, UUID> {

    @WorkspaceUnscoped("order_ref is globally unique; the PSP webhook is a signed token-bearer callback with no tenant context")
    Optional<CommerceOrderEntity> findByOrderRef(String orderRef);
}
