package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Logos are public branding served by opaque id, so the inherited
 * {@code findById} (cross-tenant) is the serving path — no tenant-scoped custom
 * query is added.
 */
public interface ChatLogoRepository extends JpaRepository<ChatLogoEntity, String> {
}
