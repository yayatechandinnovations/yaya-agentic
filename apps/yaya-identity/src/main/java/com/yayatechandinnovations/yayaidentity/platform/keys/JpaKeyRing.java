package com.yayatechandinnovations.yayaidentity.platform.keys;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.yayatechandinnovations.yayaidentity.platform.Ids.KeyId;
import com.yayatechandinnovations.yayaidentity.platform.crypto.SecretCipher;
import com.yayatechandinnovations.yayaidentity.platform.keys.SigningKey.Status;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Postgres-backed {@link KeyRing}. Bootstraps one {@code ACTIVE} RS256 key on
 * first boot (so JWKS is served immediately — the I0 exit criterion) and stores
 * private key material encrypted with the platform {@link SecretCipher}.
 *
 * <p>Because yaya-identity is the root of trust, bootstrap requires a configured
 * master key — we refuse to generate a signing key we cannot protect at rest.
 */
@Service
public class JpaKeyRing implements KeyRing {

    private static final Logger log = LoggerFactory.getLogger(JpaKeyRing.class);

    private final SigningKeyRepository repo;
    private final SecretCipher cipher;
    private final boolean bootstrap;

    public JpaKeyRing(SigningKeyRepository repo, SecretCipher cipher,
                      @Value("${yaya.identity.keys.bootstrap:true}") boolean bootstrap) {
        this.repo = repo;
        this.cipher = cipher;
        this.bootstrap = bootstrap;
    }

    @PostConstruct
    @Transactional
    public void ensureActiveKey() {
        if (repo.findFirstByStatus(Status.ACTIVE.name()).isPresent()) return;
        if (!bootstrap) {
            log.warn("No ACTIVE signing key and key bootstrap is disabled — JWKS will be empty "
                    + "and token issuance will fail until a key is provisioned.");
            return;
        }
        requireCipher();
        SigningKeyEntity minted = mint();
        repo.save(minted);
        log.info("Bootstrapped ACTIVE RS256 signing key kid={}", minted.getKid());
    }

    @Override
    @Transactional(readOnly = true)
    public SigningKey active() {
        SigningKeyEntity e = repo.findFirstByStatus(Status.ACTIVE.name())
                .orElseThrow(() -> new IllegalStateException(
                        "no ACTIVE signing key — bootstrap disabled or key not yet provisioned"));
        return toDomain(e, true);
    }

    @Override
    @Transactional(readOnly = true)
    public JWKSet publicJwks() {
        List<JWK> jwks = new ArrayList<>();
        for (SigningKeyEntity e : repo.findByStatusIn(List.of(Status.ACTIVE.name(), Status.RETIRING.name()))) {
            jwks.add(RsaKeys.toPublicJwk(toDomain(e, false)));
        }
        return new JWKSet(jwks);
    }

    @Override
    @Transactional
    public SigningKey rotate() {
        requireCipher();
        Optional<SigningKeyEntity> current = repo.findFirstByStatus(Status.ACTIVE.name());
        SigningKeyEntity next = mint();
        repo.save(next);                                  // make …
        current.ifPresent(e -> {                          // … before break
            e.setStatus(Status.RETIRING.name());
            e.setRetiringAt(Instant.now());
            repo.save(e);
        });
        log.info("Rotated signing key: new ACTIVE kid={}", next.getKid());
        return toDomain(next, true);
    }

    private SigningKeyEntity mint() {
        var pair = RsaKeys.generate();
        var pub = (java.security.interfaces.RSAPublicKey) pair.getPublic();
        var priv = (java.security.interfaces.RSAPrivateKey) pair.getPrivate();
        String kid = RsaKeys.thumbprint(pub);
        SecretCipher.Encrypted env = cipher.encrypt(RsaKeys.toPrivatePem(priv));
        return new SigningKeyEntity(kid, RsaKeys.ALG, RsaKeys.toPublicPem(pub),
                env.ciphertext(), env.iv(), Status.ACTIVE.name(), Instant.now());
    }

    private SigningKey toDomain(SigningKeyEntity e, boolean withPrivate) {
        var pub = RsaKeys.publicFromPem(e.getPublicPem());
        var priv = withPrivate
                ? RsaKeys.privateFromPem(cipher.decrypt(e.getPrivateCt(), e.getPrivateIv()))
                : null;
        return new SigningKey(new KeyId(e.getKid()), e.getAlg(), pub, priv,
                Status.valueOf(e.getStatus()), e.getCreatedAt());
    }

    private void requireCipher() {
        if (!cipher.isConfigured()) {
            throw new IllegalStateException(
                    "cannot provision a signing key: secret cipher is not configured. "
                            + "Set yaya.identity.secrets.encryption-key (env "
                            + "YAYA_IDENTITY_SECRETS_ENCRYPTION_KEY) — signing keys are the root of "
                            + "trust and are never stored unencrypted.");
        }
    }
}
