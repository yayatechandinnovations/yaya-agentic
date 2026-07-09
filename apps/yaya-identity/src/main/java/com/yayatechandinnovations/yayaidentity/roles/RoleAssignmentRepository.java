package com.yayatechandinnovations.yayaidentity.roles;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RoleAssignmentRepository extends JpaRepository<RoleAssignmentEntity, String> {

    List<RoleAssignmentEntity> findByOrgIdAndSubjectKindAndSubjectId(
            String orgId, String subjectKind, String subjectId);

    List<RoleAssignmentEntity> findByOrgIdAndSubjectKindAndSubjectIdIn(
            String orgId, String subjectKind, Collection<String> subjectIds);
}
