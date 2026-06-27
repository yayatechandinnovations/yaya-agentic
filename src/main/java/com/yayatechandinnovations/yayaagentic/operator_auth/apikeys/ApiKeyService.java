package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

import com.yayatechandinnovations.yayaagentic.config.YayaAgenticProperties;
import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.email.MailMessage;
import com.yayatechandinnovations.yayaagentic.email.OutboxMailQueue;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.OperatorAccountService.RequestMeta;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.Role;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.User;
import com.yayatechandinnovations.yayaagentic.operator_auth.accounts.UserId;
import com.yayatechandinnovations.yayaagentic.persistence.ApiKeyEntity;
import com.yayatechandinnovations.yayaagentic.persistence.ApiKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lifecycle for workspace-scoped API keys: mint, list, get, rename, revoke.
 * Every mutation writes its {@code audit_api_key_events} row inside the same
 * transaction (§8) and evicts the verifier cache where state changed (§4.4).
 *
 * <p>The raw secret exists only in the return of {@link #mint} — it is never
 * persisted and never re-derivable. See {@code docs/design/api-keys-design.md}.</p>
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final int NAME_MIN = 3;
    private static final int NAME_MAX = 64;
    private static final int PREFIX_COLLISION_RETRIES = 5;

    private final ApiKeyRepository repo;
    private final SecretGenerator secrets;
    private final ApiKeyCache cache;
    private final ApiKeyAuditWriter audit;
    private final OperatorAccountService accounts;
    private final OutboxMailQueue mail;
    private final int maxActivePerWorkspace;
    private final String publicBaseUrl;

    public ApiKeyService(ApiKeyRepository repo,
                         SecretGenerator secrets,
                         ApiKeyCache cache,
                         ApiKeyAuditWriter audit,
                         OperatorAccountService accounts,
                         OutboxMailQueue mail,
                         ApiKeysProperties apiKeysProps,
                         YayaAgenticProperties props) {
        this.repo = repo;
        this.secrets = secrets;
        this.cache = cache;
        this.audit = audit;
        this.accounts = accounts;
        this.mail = mail;
        this.maxActivePerWorkspace = apiKeysProps.maxActivePerWorkspaceOrDefault();
        this.publicBaseUrl = props.publicBaseUrl();
    }

    /** The one-time mint result. {@code secret} is echoed exactly once (§8). */
    public record Minted(ApiKey key, String secret) {}

    @Transactional
    public Minted mint(TenantId workspace, String name, Role requestedRole, OffsetDateTime expiresAt,
                       UserId creator, Role creatorRole, RequestMeta meta) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.length() < NAME_MIN || trimmed.length() > NAME_MAX) {
            throw new IllegalArgumentException("name must be " + NAME_MIN + "-" + NAME_MAX + " characters");
        }
        if (requestedRole == null || requestedRole == Role.PLATFORM_ADMIN) {
            throw new IllegalArgumentException("role must be one of OWNER, ADMIN, MEMBER, VIEWER");
        }
        // Bound minting privilege to the creator's tier (§6.2). PLATFORM_ADMIN
        // creators clear every tier via Role.atLeast.
        if (!creatorRole.atLeast(requestedRole)) {
            throw new RoleExceedsCreatorException(requestedRole, creatorRole);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        if (repo.countByWorkspaceIdAndRevokedAtIsNull(workspace.value()) >= maxActivePerWorkspace) {
            throw new KeyCapExceededException(maxActivePerWorkspace);
        }

        String secret = null;
        byte[] hash = null;
        for (int attempt = 0; attempt < PREFIX_COLLISION_RETRIES; attempt++) {
            String candidate = secrets.generate();
            byte[] candidateHash = secrets.sha256(candidate);
            if (!repo.existsBySecretHash(candidateHash)) {
                secret = candidate;
                hash = candidateHash;
                break;
            }
        }
        if (secret == null) {
            throw new IllegalStateException("could not mint a unique secret after retries");
        }

        ApiKeyEntity entity = new ApiKeyEntity(
                UUID.randomUUID(), workspace.value(), trimmed, secrets.prefixOf(secret),
                hash, requestedRole.name(), creator == null ? null : creator.value(), now, expiresAt);
        repo.save(entity);

        audit.record(ApiKeyAuditWriter.CREATE, workspace.value(), entity.getId(),
                creator == null ? null : creator.value(),
                meta == null ? null : meta.clientIp(),
                meta == null ? null : meta.userAgent(),
                detail("role", requestedRole.name(),
                        "expires_at", expiresAt == null ? null : expiresAt.toString()));

        enqueueCreationEmail(creator, workspace, trimmed, entity.getKeyPrefix(), requestedRole);
        return new Minted(ApiKey.from(entity), secret);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(TenantId workspace) {
        return repo.findByWorkspaceIdOrderByCreatedAtDesc(workspace.value())
                .stream().map(ApiKey::from).toList();
    }

    @Transactional(readOnly = true)
    public Optional<ApiKey> get(TenantId workspace, UUID keyId) {
        return repo.findByIdAndWorkspaceId(keyId, workspace.value()).map(ApiKey::from);
    }

    @Transactional
    public ApiKey rename(TenantId workspace, UUID keyId, String newName, UserId actor, RequestMeta meta) {
        String trimmed = newName == null ? "" : newName.trim();
        if (trimmed.length() < NAME_MIN || trimmed.length() > NAME_MAX) {
            throw new IllegalArgumentException("name must be " + NAME_MIN + "-" + NAME_MAX + " characters");
        }
        ApiKeyEntity e = repo.findByIdAndWorkspaceId(keyId, workspace.value())
                .orElseThrow(ApiKeyNotFoundException::new);
        String old = e.getName();
        e.setName(trimmed);
        audit.record(ApiKeyAuditWriter.RENAME, workspace.value(), e.getId(),
                actor == null ? null : actor.value(),
                meta == null ? null : meta.clientIp(),
                meta == null ? null : meta.userAgent(),
                detail("old", old, "new", trimmed));
        cache.evict(e.getKeyPrefix());
        return ApiKey.from(e);
    }

    /** Idempotent. Revoking an already-revoked key is a no-op (controller → 204). */
    @Transactional
    public void revoke(TenantId workspace, UUID keyId, UserId actor, RequestMeta meta) {
        revoke(workspace, keyId, actor, meta, "user_action", null);
    }

    @Transactional
    public void revoke(TenantId workspace, UUID keyId, UserId actor, RequestMeta meta,
                       String reason, String sourceUrl) {
        ApiKeyEntity e = repo.findByIdAndWorkspaceId(keyId, workspace.value())
                .orElseThrow(ApiKeyNotFoundException::new);
        if (e.getRevokedAt() != null) {
            return;   // already revoked — idempotent
        }
        e.setRevokedAt(OffsetDateTime.now(ZoneOffset.UTC));
        e.setRevokedByUserId(actor == null ? null : actor.value());

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("reason", reason);
        if (sourceUrl != null) d.put("source_url", sourceUrl);
        audit.record(ApiKeyAuditWriter.REVOKE, workspace.value(), e.getId(),
                actor == null ? null : actor.value(),
                meta == null ? null : meta.clientIp(),
                meta == null ? null : meta.userAgent(),
                d);
        // Evict so this node stops accepting the key immediately. (Cross-node
        // broadcast via Redis pub/sub is the §4.4 follow-up.)
        cache.evict(e.getKeyPrefix());
    }

    // ------------------------------------------------------------------

    private void enqueueCreationEmail(UserId creator, TenantId workspace, String name,
                                      String prefix, Role role) {
        if (creator == null) return;   // bootstrap / synthetic actor — nobody to notify
        Optional<User> u = accounts.findById(creator);
        if (u.isEmpty() || u.get().email() == null || u.get().email().isBlank()) return;
        String body = """
                A new API key was created in workspace %s.

                  Name:   %s
                  Prefix: %s…
                  Role:   %s

                If you didn't create this key, revoke it immediately from
                Settings → API keys%s.
                """.formatted(
                        workspace.value(), name, prefix, role.name(),
                        publicBaseUrl == null || publicBaseUrl.isBlank() ? "" : " (" + publicBaseUrl + ")");
        try {
            mail.enqueue(new MailMessage(
                    u.get().email(),
                    "A new API key was created in workspace " + workspace.value(),
                    body, null, "api-key-created",
                    "api-key-created:" + prefix + ":" + workspace.value()));
        } catch (RuntimeException ex) {
            // Notification is best-effort; never fail the mint on a mail hiccup.
            log.warn("api-key creation email enqueue failed (key still created): {}", ex.toString());
        }
    }

    private static Map<String, Object> detail(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
