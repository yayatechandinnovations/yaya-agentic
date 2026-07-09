package com.yayatechandinnovations.yayaidentity.platform.keys;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persistent form of a {@link SigningKey}. The private key is stored as an
 * AES-256-GCM envelope ({@code privateCt} + {@code privateIv}) — never plaintext.
 */
@Entity
@Table(name = "signing_keys")
public class SigningKeyEntity {

    @Id
    @Column(name = "kid")
    private String kid;

    @Column(nullable = false)
    private String alg;

    @Column(name = "public_pem", nullable = false)
    private String publicPem;

    @Column(name = "private_ct", nullable = false)
    private String privateCt;

    @Column(name = "private_iv", nullable = false)
    private String privateIv;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "retiring_at")
    private Instant retiringAt;

    protected SigningKeyEntity() {}

    public SigningKeyEntity(String kid, String alg, String publicPem, String privateCt,
                            String privateIv, String status, Instant createdAt) {
        this.kid = kid;
        this.alg = alg;
        this.publicPem = publicPem;
        this.privateCt = privateCt;
        this.privateIv = privateIv;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getKid() { return kid; }
    public String getAlg() { return alg; }
    public String getPublicPem() { return publicPem; }
    public String getPrivateCt() { return privateCt; }
    public String getPrivateIv() { return privateIv; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRetiringAt() { return retiringAt; }

    public void setStatus(String status) { this.status = status; }
    public void setRetiringAt(Instant retiringAt) { this.retiringAt = retiringAt; }
}
