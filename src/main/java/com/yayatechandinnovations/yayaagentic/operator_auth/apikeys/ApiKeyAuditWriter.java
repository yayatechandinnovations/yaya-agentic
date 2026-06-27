package com.yayatechandinnovations.yayaagentic.operator_auth.apikeys;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayaagentic.persistence.AuditApiKeyEventEntity;
import com.yayatechandinnovations.yayaagentic.persistence.AuditApiKeyEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Writes {@code audit_api_key_events}. Two callers (§12):
 * <ul>
 *   <li>{@link #record} — synchronous, inside the service tx for state changes
 *       ({@code CREATE}/{@code REVOKE}/{@code RENAME}) so audit and state never
 *       drift.</li>
 *   <li>{@link #recordDeniedAsync} — fire-and-forget for {@code USE_DENIED}
 *       (the deny path runs in the filter and must not block the request).</li>
 * </ul>
 *
 * The IP column is chosen by {@link AuditIpRecorder} from the {@code kind};
 * no caller picks it.
 */
@Component
public class ApiKeyAuditWriter {

    public static final String CREATE = "CREATE";
    public static final String REVOKE = "REVOKE";
    public static final String RENAME = "RENAME";
    public static final String USE_DENIED = "USE_DENIED";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuditWriter.class);

    private final AuditApiKeyEventRepository repo;
    private final AuditIpRecorder ip;
    private final ApiKeyBackgroundExecutor background;
    private final ObjectMapper json;

    public ApiKeyAuditWriter(AuditApiKeyEventRepository repo, AuditIpRecorder ip,
                             ApiKeyBackgroundExecutor background, ObjectMapper json) {
        this.repo = repo;
        this.ip = ip;
        this.background = background;
        this.json = json;
    }

    /** Synchronous state-change audit. MUST run inside the caller's transaction. */
    public void record(String kind, String workspaceId, UUID apiKeyId, UUID actorUserId,
                       String clientIp, String userAgent, Map<String, ?> detail) {
        AuditIpRecorder.Columns cols = ip.columnsFor(kind, clientIp);
        repo.save(new AuditApiKeyEventEntity(
                workspaceId, apiKeyId, actorUserId, kind,
                toJson(detail), cols.clientIp(), cols.clientIpHash(),
                userAgent, OffsetDateTime.now(ZoneOffset.UTC)));
    }

    /** Fire-and-forget deny audit from the filter. Failures are swallowed. */
    public void recordDeniedAsync(String workspaceId, UUID apiKeyId, String clientIp,
                                  String userAgent, Map<String, ?> detail) {
        background.submit(() -> {
            try {
                AuditIpRecorder.Columns cols = ip.columnsFor(USE_DENIED, clientIp);
                repo.save(new AuditApiKeyEventEntity(
                        workspaceId, apiKeyId, null, USE_DENIED,
                        toJson(detail), cols.clientIp(), cols.clientIpHash(),
                        userAgent, OffsetDateTime.now(ZoneOffset.UTC)));
            } catch (RuntimeException e) {
                // Deny audit is best-effort observability; never surface to the caller.
                log.debug("api-key USE_DENIED audit write failed (dropped): {}", e.toString());
            }
        });
    }

    private String toJson(Map<String, ?> detail) {
        if (detail == null || detail.isEmpty()) return null;
        try {
            return json.writeValueAsString(detail);
        } catch (Exception e) {
            return null;
        }
    }
}
