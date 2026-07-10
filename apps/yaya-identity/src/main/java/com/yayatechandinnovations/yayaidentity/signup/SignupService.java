package com.yayatechandinnovations.yayaidentity.signup;

import com.yayatechandinnovations.yayaidentity.credentials.CredentialService;
import com.yayatechandinnovations.yayaidentity.organizations.Organization;
import com.yayatechandinnovations.yayaidentity.organizations.OrganizationService;
import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import com.yayatechandinnovations.yayaidentity.platform.Ids.UserId;
import com.yayatechandinnovations.yayaidentity.roles.AssignmentSubject;
import com.yayatechandinnovations.yayaidentity.roles.Role;
import com.yayatechandinnovations.yayaidentity.roles.RoleAssignmentService;
import com.yayatechandinnovations.yayaidentity.roles.RoleService;
import com.yayatechandinnovations.yayaidentity.roles.Scope;
import com.yayatechandinnovations.yayaidentity.users.User;
import com.yayatechandinnovations.yayaidentity.users.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Phone-verified org self-registration, mirroring {@code OwnerBootstrap}'s
 * shape: verify → ACTIVE user + org + membership + system {@code owner} role,
 * all in one transaction. Until the OTP is confirmed the user stays INVITED
 * (cannot authenticate) and the org does not exist at all — an unverified
 * signup reserves nothing but an email.
 *
 * <p>OTP start/check run OUTSIDE transactions (Twilio is a remote call); the
 * finalize step re-locks the pending row so a double-verify can't create two
 * orgs. Registration is disabled by default and rate-limited per phone and per
 * caller IP because each OTP send may be billable SMS.
 */
@Service
public class SignupService {

    static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");
    static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final PendingSignupRepository pendings;
    private final UserService users;
    private final OrganizationService orgs;
    private final RoleService roles;
    private final RoleAssignmentService assignments;
    private final CredentialService credentials;
    private final OtpChannel otp;
    private final TransactionTemplate tx;
    private final SignupProperties props;
    private final SignupRateLimiter phoneLimiter;
    private final SignupRateLimiter ipLimiter;

    public SignupService(PendingSignupRepository pendings, UserService users,
                         OrganizationService orgs, RoleService roles,
                         RoleAssignmentService assignments, CredentialService credentials,
                         OtpChannel otp, PlatformTransactionManager txManager,
                         SignupProperties props) {
        this.pendings = pendings;
        this.users = users;
        this.orgs = orgs;
        this.roles = roles;
        this.assignments = assignments;
        this.credentials = credentials;
        this.otp = otp;
        this.tx = new TransactionTemplate(txManager);
        this.props = props;
        this.phoneLimiter = new SignupRateLimiter(props.maxSendsPerPhonePerHour(), Duration.ofHours(1));
        this.ipLimiter = new SignupRateLimiter(props.maxSendsPerIpPerHour(), Duration.ofHours(1));
    }

    public RegisterResult register(String orgName, String displayName, String email,
                                   String phone, String password, String clientIp) {
        requireEnabled();
        String normalizedEmail = requireEmail(email);
        String normalizedPhone = requirePhone(phone);
        requireText(orgName, "orgName");
        requireText(displayName, "displayName");
        acquireSendBudget(normalizedPhone, clientIp);

        PendingSignupEntity pending = tx.execute(status -> {
            User user = users.byEmail(normalizedEmail)
                    .map(existing -> {
                        if (existing.status() != User.Status.INVITED) {
                            throw new UserService.EmailAlreadyInUseException(normalizedEmail);
                        }
                        // A retry (or an invitee choosing self-signup) may fix details.
                        return users.updateProfile(existing.id(), normalizedPhone, displayName);
                    })
                    .orElseGet(() -> users.createPerson(normalizedEmail, normalizedPhone, displayName));
            if (users.activeByPhone(normalizedPhone).isPresent()) {
                throw new PhoneAlreadyInUseException();
            }
            credentials.setPassword(user.id(), password);
            for (PendingSignupEntity stale : pendings.findByUserIdAndStatus(
                    user.id().value(), "PENDING")) {
                stale.setStatus("EXPIRED");
            }
            Instant now = Instant.now();
            return pendings.save(new PendingSignupEntity(
                    "sgn_" + UUID.randomUUID().toString().replace("-", ""),
                    user.id().value(), orgName.trim(), normalizedPhone,
                    now, now.plus(Duration.ofSeconds(props.pendingTtlSeconds()))));
        });

        Optional<String> raw = otp.start(new UserId(pending.getUserId()), normalizedPhone);
        markOtpSent(pending.getId());
        return new RegisterResult(pending.getId(), echoOf(raw));
    }

    public VerifiedResult verify(String signupId, String code) {
        requireEnabled();
        PendingSignupEntity pending = pendingOrThrow(signupId);
        if (!otp.check(new UserId(pending.getUserId()), pending.getPhone(), code)) {
            throw new InvalidOtpException();
        }
        return tx.execute(status -> {
            PendingSignupEntity locked = pendings.findWithLockById(signupId)
                    .filter(p -> "PENDING".equals(p.getStatus()))
                    .orElseThrow(InvalidSignupException::new);
            UserId userId = new UserId(locked.getUserId());
            users.setStatus(userId, User.Status.ACTIVE);
            Organization org = orgs.create(locked.getOrgName());
            orgs.addMember(org.id(), userId);
            Role owner = roles.systemRole("owner")
                    .orElseThrow(() -> new IllegalStateException("system role 'owner' missing"));
            assignments.assign(org.id(), AssignmentSubject.user(userId), owner.id(),
                    new Scope.Org(new OrgId(org.id().value())));
            locked.setStatus("VERIFIED");
            return new VerifiedResult(userId.value(), org.id().value(), locked.getOrgName());
        });
    }

    public RegisterResult resend(String signupId, String clientIp) {
        requireEnabled();
        PendingSignupEntity pending = pendingOrThrow(signupId);
        Instant now = Instant.now();
        if (pending.getOtpSentAt() != null && now.isBefore(
                pending.getOtpSentAt().plus(Duration.ofSeconds(props.resendCooldownSeconds())))) {
            throw new RateLimitedException();
        }
        acquireSendBudget(pending.getPhone(), clientIp);
        Optional<String> raw = otp.start(new UserId(pending.getUserId()), pending.getPhone());
        markOtpSent(signupId);
        return new RegisterResult(signupId, echoOf(raw));
    }

    private PendingSignupEntity pendingOrThrow(String signupId) {
        PendingSignupEntity pending = pendings.findById(signupId)
                .filter(p -> "PENDING".equals(p.getStatus()))
                .orElseThrow(InvalidSignupException::new);
        if (Instant.now().isAfter(pending.getExpiresAt())) {
            tx.executeWithoutResult(s -> pendings.findWithLockById(signupId)
                    .ifPresent(p -> p.setStatus("EXPIRED")));
            throw new SignupExpiredException();
        }
        return pending;
    }

    private void markOtpSent(String signupId) {
        tx.executeWithoutResult(s -> pendings.findById(signupId)
                .ifPresent(p -> p.markOtpSent(Instant.now())));
    }

    private void acquireSendBudget(String phone, String clientIp) {
        Instant now = Instant.now();
        if (!phoneLimiter.tryAcquire(phone, now) || !ipLimiter.tryAcquire(clientIp, now)) {
            throw new RateLimitedException();
        }
    }

    private Optional<String> echoOf(Optional<String> raw) {
        return props.otpEchoEnabled() ? raw : Optional.empty();
    }

    private void requireEnabled() {
        if (!props.enabled()) {
            throw new SignupDisabledException();
        }
    }

    private static String requireEmail(String email) {
        if (email == null || !EMAIL.matcher(email.trim()).matches()) {
            throw new IllegalArgumentException("a valid email is required");
        }
        return email.trim();
    }

    private static String requirePhone(String phone) {
        String normalized = phone == null ? "" : phone.replaceAll("[\\s()-]", "");
        if (!E164.matcher(normalized).matches()) {
            throw new IllegalArgumentException("phone must be E.164, e.g. +18095551234");
        }
        return normalized;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    public record RegisterResult(String signupId, Optional<String> devOtp) {}

    public record VerifiedResult(String userId, String orgId, String orgName) {}

    public static class SignupDisabledException extends RuntimeException {
        public SignupDisabledException() { super("self-registration is disabled"); }
    }

    public static class InvalidSignupException extends RuntimeException {
        public InvalidSignupException() { super("unknown or already-completed signup"); }
    }

    public static class SignupExpiredException extends RuntimeException {
        public SignupExpiredException() { super("signup expired — register again"); }
    }

    public static class InvalidOtpException extends RuntimeException {
        public InvalidOtpException() { super("invalid or expired verification code"); }
    }

    public static class PhoneAlreadyInUseException extends RuntimeException {
        public PhoneAlreadyInUseException() { super("phone number already in use"); }
    }

    public static class RateLimitedException extends RuntimeException {
        public RateLimitedException() { super("too many verification attempts — try again later"); }
    }

    public static class OtpDeliveryException extends RuntimeException {
        public OtpDeliveryException() { super("could not deliver the verification code"); }
    }
}
