package com.yayatechandinnovations.yayaagentic.operator_auth.accounts;

import com.yayatechandinnovations.yayaagentic.core.Ids.TenantId;
import com.yayatechandinnovations.yayaagentic.persistence.AuditUserEventEntity;
import com.yayatechandinnovations.yayaagentic.persistence.AuditUserEventRepository;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Centralised audit-row writer for {@code audit_user_events}. Keeps the
 * canonical {@code kind} taxonomy in one place (see Backend-Data §2.5)
 * and serialises {@code detail_json} without dragging Jackson into every
 * service.
 *
 * <p>Detail is a flat map; values are stringified. Audit rows never carry
 * raw PII — pass IDs and hashes only.</p>
 */
@Component
public class AccountAuditWriter {

    private final AuditUserEventRepository repo;

    public AccountAuditWriter(AuditUserEventRepository repo) {
        this.repo = repo;
    }

    public void write(String kind,
                      UserId userId,
                      TenantId workspaceId,
                      OperatorAccountService.RequestMeta meta,
                      Map<String, ?> detail) {
        UUID uid = userId == null ? null : userId.value();
        String wid = workspaceId == null ? null : workspaceId.value();
        String ip = meta == null ? null : meta.clientIp();
        String ua = meta == null ? null : meta.userAgent();
        String detailJson = detail == null || detail.isEmpty() ? null : toJson(detail);

        repo.save(new AuditUserEventEntity(
                uid, wid, kind, detailJson, ip, ua,
                OffsetDateTime.now(ZoneOffset.UTC)));
    }

    /** Minimal flat-map serializer; values are JSON-stringified (numbers/bools/null/strings). */
    private static String toJson(Map<String, ?> detail) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        // Preserve insertion order so logs read predictably.
        Map<String, ?> ordered = detail instanceof LinkedHashMap<?, ?>
                ? detail
                : new LinkedHashMap<>(detail);
        for (Map.Entry<String, ?> e : ordered.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(escapeString(e.getKey())).append(':');
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Boolean || v instanceof Number) sb.append(v);
            else sb.append(escapeString(v.toString()));
        }
        return sb.append('}').toString();
    }

    private static String escapeString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
