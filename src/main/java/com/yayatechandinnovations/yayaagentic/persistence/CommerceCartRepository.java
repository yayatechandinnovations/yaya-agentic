package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Durable cart projection. Keyed by (globally-unique) session id; read/written/
 * deleted by PK only, so no tenant-scoped custom finders are needed yet. A
 * tenant-scoped recovery sweep will add one when that path lands.
 */
public interface CommerceCartRepository extends JpaRepository<CommerceCartEntity, UUID> {
}
