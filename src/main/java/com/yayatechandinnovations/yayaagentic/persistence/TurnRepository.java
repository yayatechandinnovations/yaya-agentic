package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TurnRepository extends JpaRepository<TurnEntity, UUID> {

    List<TurnEntity> findBySessionIdOrderByIdxAsc(UUID sessionId);
}
