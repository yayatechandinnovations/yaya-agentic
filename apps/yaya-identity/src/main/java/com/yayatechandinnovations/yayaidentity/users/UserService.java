package com.yayatechandinnovations.yayaidentity.users;

import com.yayatechandinnovations.yayaidentity.credentials.CredentialService;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import com.yayatechandinnovations.yayaidentity.platform.outbox.OutboxRelay;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * User lifecycle (design §5.2). Creation emits a {@code user.created} outbox
 * event in the same transaction so relying parties can react. Email uniqueness
 * is enforced case-insensitively by the DB; a clash surfaces as a clear conflict.
 */
@Service
public class UserService {

    private final UserRepository repo;
    private final OutboxRelay outbox;
    private final CredentialService credentials;

    public UserService(UserRepository repo, OutboxRelay outbox, CredentialService credentials) {
        this.repo = repo;
        this.outbox = outbox;
        this.credentials = credentials;
    }

    /** A human identity. Starts {@code INVITED} until a credential is set (I1c). */
    @Transactional
    public User createPerson(String email, String phone, String displayName) {
        return create(email, phone, displayName, User.Kind.PERSON, User.Status.INVITED);
    }

    /** A machine identity (service account); usable immediately. */
    @Transactional
    public User createServiceAccount(String displayName) {
        return create(null, null, displayName, User.Kind.SERVICE, User.Status.ACTIVE);
    }

    private User create(String email, String phone, String displayName,
                        User.Kind kind, User.Status status) {
        String id = "usr_" + UUID.randomUUID().toString().replace("-", "");
        UserEntity entity = new UserEntity(id, email, phone, displayName,
                kind.name(), status.name(), Instant.now());
        try {
            repo.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            throw new EmailAlreadyInUseException(email);
        }
        outbox.append("user", id, "user.created",
                "{\"id\":\"" + id + "\",\"kind\":\"" + kind.name() + "\"}");
        return entity.toDomain();
    }

    @Transactional(readOnly = true)
    public Optional<User> byId(UserId id) {
        return repo.findById(id.value()).map(UserEntity::toDomain);
    }

    @Transactional(readOnly = true)
    public Optional<User> byEmail(String email) {
        return repo.findByEmailIgnoreCase(email).map(UserEntity::toDomain);
    }

    /** Lock or disable — both stop authentication and (from I1c) revoke sessions. */
    @Transactional
    public User setStatus(UserId id, User.Status status) {
        UserEntity e = repo.findById(id.value())
                .orElseThrow(() -> new IllegalArgumentException("no such user: " + id.value()));
        e.setStatus(status.name());
        return e.toDomain();
    }

    /**
     * Delete a user: scrub PII, revoke credentials, tombstone the row (AC-44). The
     * id survives so audit references remain valid; a re-signup can reuse the email.
     */
    @Transactional
    public void deleteUser(UserId id) {
        UserEntity e = repo.findById(id.value())
                .orElseThrow(() -> new IllegalArgumentException("no such user: " + id.value()));
        credentials.revokeAll(id);
        e.scrubPii(Instant.now());
        outbox.append("user", id.value(), "user.deleted", "{\"id\":\"" + id.value() + "\"}");
    }

    public static class EmailAlreadyInUseException extends RuntimeException {
        public EmailAlreadyInUseException(String email) {
            super("email already in use: " + email);
        }
    }
}
