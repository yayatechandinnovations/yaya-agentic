package com.yayatechandinnovations.yayaidentity.users;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, String> {

    @Query("select u from UserEntity u where lower(u.primaryEmail) = lower(:email)")
    Optional<UserEntity> findByEmailIgnoreCase(@Param("email") String email);

    Optional<UserEntity> findFirstByPhoneAndStatus(String phone, String status);
}
