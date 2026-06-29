package com.yayatechandinnovations.yayaagentic.channel.auth;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory capture of the actual HTTP exchange an {@link HttpAuthResolver}
 * had with a tenant's resolver webhook — the request yaya sent and the
 * response it got back — for the playground auth-flow inspector. The durable
 * {@code auth_resolver_calls} table records only status/latency/error; this is
 * the request/response body detail an operator needs to debug "why did my
 * resolver not return an identity?".
 *
 * <p>Keyed by {@code tenantId|callerSha256} (the resolver runs before the
 * engine mints a session id, so the inspector recovers it by caller hash, the
 * same way it recovers the audit row). Single-instance + bounded, matching the
 * other playground stores.
 *
 * <p>SECURITY: everything written here is redacted at the source —
 * {@link HttpAuthResolver} strips its own outbound {@code Authorization}
 * credential and the JWT in the response body BEFORE recording. The store
 * never holds a usable token or secret. See {@code
 * playground-auth-flow-inspector-design.md} §10 and {@code
 * channel-auth-resolver-design.md} §13.1.
 */
@Component
public class ResolverExchangeStore {

    private static final int MAX_KEYS = 10_000;
    private static final int MAX_PER_KEY = 10;

    /** One captured request/response. All fields are already redaction-safe. */
    public record Exchange(String requestId,
                           String method,
                           String url,
                           Map<String, String> requestHeaders,
                           Map<String, Object> requestBody,
                           Integer responseStatus,
                           String responseBody,
                           String error,
                           String at) {}

    private final Map<String, List<Exchange>> byCaller = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Exchange>> eldest) {
                    return size() > MAX_KEYS;
                }
            });

    public void record(String tenantId, String callerSha256, Exchange exchange) {
        if (tenantId == null || callerSha256 == null || exchange == null) return;
        String key = key(tenantId, callerSha256);
        synchronized (byCaller) {
            List<Exchange> list = byCaller.computeIfAbsent(key, k -> new ArrayList<>());
            if (list.size() >= MAX_PER_KEY) list.remove(0);
            list.add(exchange);
        }
    }

    public List<Exchange> forCaller(String tenantId, String callerSha256) {
        if (tenantId == null || callerSha256 == null) return List.of();
        synchronized (byCaller) {
            List<Exchange> list = byCaller.get(key(tenantId, callerSha256));
            return list == null ? List.of() : List.copyOf(list);
        }
    }

    public void wipe(String tenantId, String callerSha256) {
        if (tenantId == null || callerSha256 == null) return;
        byCaller.remove(key(tenantId, callerSha256));
    }

    private static String key(String tenantId, String callerSha256) {
        return tenantId + "|" + callerSha256;
    }
}
