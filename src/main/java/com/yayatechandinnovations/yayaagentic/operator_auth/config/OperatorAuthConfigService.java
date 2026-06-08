package com.yayatechandinnovations.yayaagentic.operator_auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.operator_auth.delegate.FailureMapping;
import com.yayatechandinnovations.yayaagentic.operator_auth.delegate.HttpDelegateConfig;
import com.yayatechandinnovations.yayaagentic.operator_auth.delegate.IdentityMapping;
import com.yayatechandinnovations.yayaagentic.operator_auth.delegate.RequestShape;
import com.yayatechandinnovations.yayaagentic.operator_auth.delegate.SuccessCriteria;
import com.yayatechandinnovations.yayaagentic.persistence.OperatorAuthConfigEntity;
import com.yayatechandinnovations.yayaagentic.persistence.OperatorAuthConfigRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * The singleton store for operator-auth strategy config. YAML is the
 * first-boot seed; after that the DB row is the source of truth so the
 * admin UI can toggle strategies and change the bootstrap password
 * without a restart.
 *
 * <p>The delegate {@code sharedSecret} round-trips through {@link SecretCipher}
 * — the plaintext only exists in memory between read/write at this layer
 * and is never logged.</p>
 */
@Service
public class OperatorAuthConfigService {

    private static final Logger log = LoggerFactory.getLogger(OperatorAuthConfigService.class);
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin";

    private final OperatorAuthConfigRepository repo;
    private final ObjectMapper json;
    private final PasswordEncoder encoder;
    private final SecretCipher cipher;
    private final YayaAgenticProperties props;

    public OperatorAuthConfigService(OperatorAuthConfigRepository repo,
                                     ObjectMapper json,
                                     PasswordEncoder encoder,
                                     SecretCipher cipher,
                                     YayaAgenticProperties props) {
        this.repo = repo;
        this.json = json;
        this.encoder = encoder;
        this.cipher = cipher;
        this.props = props;
    }

    @PostConstruct
    void seedIfMissing() {
        // Eager seed so the first request doesn't have to. Wrapped in
        // try because tests sometimes lack the DB at construction time;
        // in that case the lazy path in current() handles it.
        try {
            ensureRow();
        } catch (Exception e) {
            log.debug("operator-auth config seed deferred to first lookup: {}", e.getMessage());
        }
    }

    @Transactional
    public Snapshot current() {
        OperatorAuthConfigEntity e = ensureRow();
        return toSnapshot(e);
    }

    @Transactional
    public BootstrapState bootstrapState() {
        OperatorAuthConfigEntity e = ensureRow();
        return new BootstrapState(e.isBootstrapEnabled(),
                e.getBootstrapUsername(), e.getBootstrapPasswordHash());
    }

    @Transactional
    public HttpDelegateConfig delegateState() {
        return current().delegate();
    }

    @Transactional
    public Snapshot saveBootstrap(Boolean enabled, String newPassword, String updatedBy) {
        OperatorAuthConfigEntity e = ensureRow();
        if (enabled != null) e.setBootstrapEnabled(enabled);
        if (newPassword != null && !newPassword.isEmpty()) {
            e.setBootstrapPasswordHash(encoder.encode(newPassword));
        }
        e.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        e.setUpdatedBy(updatedBy);
        return toSnapshot(repo.save(e));
    }

    @Transactional
    public Snapshot saveDelegate(HttpDelegateConfig cfg, String updatedBy) {
        OperatorAuthConfigEntity e = ensureRow();
        e.setHttpDelegateEnabled(cfg.enabled());
        e.setHttpDelegateUrl(cfg.url());
        e.setHttpDelegateTimeoutMs((int) cfg.timeout().toMillis());
        e.setHttpDelegateRequireHttps(cfg.requireHttps());
        e.setHttpDelegateRequestJson(writeJson(cfg.request()));
        e.setHttpDelegateSuccessJson(writeJson(cfg.success()));
        e.setHttpDelegateIdentityJson(writeJson(cfg.identity()));
        e.setHttpDelegateFailureJson(writeJson(cfg.failure()));
        // Only rewrite the secret if a new one was provided — `null` =
        // "keep existing". This lets the admin UI PUT the config without
        // having to send the secret every time.
        if (cfg.sharedSecret() != null && !cfg.sharedSecret().isEmpty()) {
            e.setHttpDelegateSecretEnc(cipher.encrypt(cfg.sharedSecret()));
        }
        e.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        e.setUpdatedBy(updatedBy);
        return toSnapshot(repo.save(e));
    }

    // ------------------------------------------------------------------

    private OperatorAuthConfigEntity ensureRow() {
        Optional<OperatorAuthConfigEntity> maybe = repo.findById(OperatorAuthConfigEntity.SINGLETON_ID);
        if (maybe.isPresent()) return maybe.get();
        return repo.save(seedFromYaml());
    }

    private OperatorAuthConfigEntity seedFromYaml() {
        YayaAgenticProperties.OperatorAuth.Bootstrap b =
                props.operatorAuth() == null ? null : props.operatorAuth().bootstrap();

        boolean enabled = b == null || b.enabled();
        String username = b == null || isBlank(b.username()) ? DEFAULT_USERNAME : b.username();

        String hash;
        boolean usingDefaults;
        if (b != null && !isBlank(b.passwordHash())) {
            hash = b.passwordHash();
            usingDefaults = false;
        } else {
            String plain = b == null || isBlank(b.password()) ? DEFAULT_PASSWORD : b.password();
            usingDefaults = DEFAULT_USERNAME.equals(username) && DEFAULT_PASSWORD.equals(plain);
            hash = encoder.encode(plain);
        }

        if (usingDefaults) {
            log.warn("[SECURITY] Seeding operator_auth_config with default credentials ({} / {}). "
                    + "Either set YAYA_BOOTSTRAP_PASSWORD_HASH before first boot, or change the "
                    + "password via the admin UI immediately after first login.",
                    DEFAULT_USERNAME, DEFAULT_PASSWORD);
        } else {
            log.info("Seeding operator_auth_config with bootstrap username '{}'.", username);
        }

        return new OperatorAuthConfigEntity(
                enabled, username, hash,
                false, null, null,
                5000, true,
                writeJson(RequestShape.defaults()),
                writeJson(SuccessCriteria.defaults()),
                writeJson(IdentityMapping.defaults()),
                writeJson(FailureMapping.defaults()),
                OffsetDateTime.now(ZoneOffset.UTC), "system");
    }

    private Snapshot toSnapshot(OperatorAuthConfigEntity e) {
        RequestShape req = readJson(e.getHttpDelegateRequestJson(), RequestShape.class,
                RequestShape.defaults());
        SuccessCriteria suc = readJson(e.getHttpDelegateSuccessJson(), SuccessCriteria.class,
                SuccessCriteria.defaults());
        IdentityMapping ide = readJson(e.getHttpDelegateIdentityJson(), IdentityMapping.class,
                IdentityMapping.defaults());
        FailureMapping fai = readJson(e.getHttpDelegateFailureJson(), FailureMapping.class,
                FailureMapping.defaults());

        String secret = null;
        try {
            secret = cipher.decrypt(e.getHttpDelegateSecretEnc());
        } catch (Exception ex) {
            // Cipher mismatch (key rotated incorrectly) — surface as
            // "no secret" so the operator can re-enter it.
            log.warn("operator_auth_config: failed to decrypt delegate secret — was the key rotated?", ex);
        }

        HttpDelegateConfig delegate = new HttpDelegateConfig(
                e.isHttpDelegateEnabled(),
                e.getHttpDelegateUrl(),
                secret,
                Duration.ofMillis(e.getHttpDelegateTimeoutMs()),
                e.isHttpDelegateRequireHttps(),
                req, suc, ide, fai);

        boolean secretPresent = e.getHttpDelegateSecretEnc() != null && e.getHttpDelegateSecretEnc().length > 0;

        return new Snapshot(
                new BootstrapState(e.isBootstrapEnabled(),
                        e.getBootstrapUsername(), e.getBootstrapPasswordHash()),
                delegate, secretPresent,
                e.getUpdatedAt(), e.getUpdatedBy());
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialise operator-auth config", e);
        }
    }

    private <T> T readJson(String value, Class<T> type, T fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            log.warn("operator_auth_config: failed to parse {} — falling back to defaults", type.getSimpleName(), e);
            return fallback;
        }
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    // ------------------------------------------------------------------

    public record Snapshot(
            BootstrapState bootstrap,
            HttpDelegateConfig delegate,
            boolean delegateSecretPresent,
            OffsetDateTime updatedAt,
            String updatedBy
    ) {}

    public record BootstrapState(boolean enabled, String username, String passwordHash) {}
}
