package com.yayatechandinnovations.yayaidentity.platform.keys;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SigningKeyRepository extends JpaRepository<SigningKeyEntity, String> {

    Optional<SigningKeyEntity> findFirstByStatus(String status);

    List<SigningKeyEntity> findByStatusIn(List<String> statuses);
}
