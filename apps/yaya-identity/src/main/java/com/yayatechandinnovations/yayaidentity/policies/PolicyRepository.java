package com.yayatechandinnovations.yayaidentity.policies;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PolicyRepository extends JpaRepository<PolicyEntity, String> {

    List<PolicyEntity> findByOrgIdAndActiveTrue(String orgId);

    List<PolicyEntity> findByOrgId(String orgId);
}
