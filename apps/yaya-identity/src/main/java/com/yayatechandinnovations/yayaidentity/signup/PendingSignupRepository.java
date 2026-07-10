package com.yayatechandinnovations.yayaidentity.signup;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface PendingSignupRepository extends JpaRepository<PendingSignupEntity, String> {

    /** Finalization races (double verify) are settled by this lock, not by luck. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PendingSignupEntity> findWithLockById(String id);

    List<PendingSignupEntity> findByUserIdAndStatus(String userId, String status);
}
