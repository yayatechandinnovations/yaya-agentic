package com.yayatechandinnovations.yayaidentity.credentials;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CredentialRepository extends JpaRepository<CredentialEntity, String> {

    Optional<CredentialEntity> findFirstByUserIdAndKindAndStatusOrderByCreatedAtDesc(
            String userId, String kind, String status);

    List<CredentialEntity> findByUserIdAndKindAndStatus(String userId, String kind, String status);

    List<CredentialEntity> findByUserId(String userId);
}
