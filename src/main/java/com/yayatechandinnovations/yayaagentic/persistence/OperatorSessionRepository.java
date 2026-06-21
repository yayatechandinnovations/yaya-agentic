package com.yayatechandinnovations.yayaagentic.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface OperatorSessionRepository extends JpaRepository<OperatorSessionEntity, String> {

    @Modifying
    @Query("delete from OperatorSessionEntity s where s.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") OffsetDateTime cutoff);

    /**
     * Used by password-reset confirm to invalidate every session for a user.
     * Matches against {@code operator_subject} because that's where
     * {@code UserPasswordOperatorAuthenticator} stores the user UUID until
     * V25 adds a dedicated {@code user_id} column.
     */
    @Modifying
    @Query("delete from OperatorSessionEntity s where s.operatorSubject = :subject")
    int deleteByOperatorSubject(@Param("subject") String subject);
}
