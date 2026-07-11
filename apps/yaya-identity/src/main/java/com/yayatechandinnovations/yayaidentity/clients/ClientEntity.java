package com.yayatechandinnovations.yayaidentity.clients;

import com.yayatechandinnovations.yayaidentity.platform.Ids.OrgId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "clients")
public class ClientEntity {

    @Id
    private String id;

    @Column(name = "client_id", nullable = false, unique = true)
    private String clientId;

    @Column(name = "org_id")
    private String orgId;

    private String name;

    @Column(nullable = false)
    private String type;

    @Column(name = "secret_hash")
    private String secretHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "grants_json", nullable = false)
    private List<String> grants;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redirect_uris_json")
    private List<String> redirectUris;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scopes_json")
    private List<String> scopes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audiences_json")
    private List<String> audiences;

    @Column(name = "require_pkce", nullable = false)
    private boolean requirePkce;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ClientEntity() {}

    public ClientEntity(String id, String clientId, String orgId, String name, Client.Type type,
                        String secretHash, List<String> grants, List<String> redirectUris,
                        List<String> scopes, List<String> audiences, boolean requirePkce,
                        Instant createdAt) {
        this.id = id;
        this.clientId = clientId;
        this.orgId = orgId;
        this.name = name;
        this.type = type.name();
        this.secretHash = secretHash;
        this.grants = grants;
        this.redirectUris = redirectUris;
        this.scopes = scopes;
        this.audiences = audiences;
        this.requirePkce = requirePkce;
        this.createdAt = createdAt;
    }

    public Client toDomain() {
        return new Client(id, clientId, orgId == null ? null : new OrgId(orgId), name,
                Client.Type.valueOf(type), safe(grants), safe(redirectUris), safe(scopes),
                safe(audiences), requirePkce);
    }

    private static List<String> safe(List<String> l) {
        return l == null ? List.of() : l;
    }

    public String getId() { return id; }
    public String getClientId() { return clientId; }
    public Client.Type type() { return Client.Type.valueOf(type); }
    public String getSecretHash() { return secretHash; }
    public List<String> getGrants() { return safe(grants); }
    public List<String> getRedirectUris() { return safe(redirectUris); }
    public List<String> getScopes() { return safe(scopes); }
    public List<String> getAudiences() { return safe(audiences); }
    public boolean isRequirePkce() { return requirePkce; }

    public void setSecretHash(String secretHash) { this.secretHash = secretHash; }
    public void setRedirectUris(List<String> redirectUris) { this.redirectUris = redirectUris; }
}
