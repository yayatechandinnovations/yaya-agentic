package com.yayatechandinnovations.yayaagentic.api;

import org.springframework.http.HttpStatus;
import java.util.Map;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin-layer error with a stable, machine-readable code (e.g. {@code
 * unknown_tenant}, {@code bad_host_base_url}). The Flutter admin UI binds
 * field-level errors against these codes — they must not drift.
 */
public class AdminApiException extends ResponseStatusException {

    private final String code;
    private final Map<String, String> fieldErrors;
    private final Integer currentVersion;

    public AdminApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null, null);
    }

    private AdminApiException(HttpStatus status, String code, String message,
                              Map<String, String> fieldErrors, Integer currentVersion) {
        super(status, message);
        this.code = code;
        this.fieldErrors = fieldErrors;
        this.currentVersion = currentVersion;
    }

    public String code() { return code; }

    public Map<String, String> fieldErrors() { return fieldErrors; }

    public Integer currentVersion() { return currentVersion; }

    /** A 400 the console renders under {@code field} rather than as a banner. */
    public static AdminApiException invalidField(String field, String message) {
        // BAD_REQUEST, not a new code: these replace plain 400s whose error clients already read.
        return new AdminApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message,
                Map.of(field, message), null);
    }

    /**
     * A versioned save based on a version that is no longer the latest: someone
     * published in between. Opt-in via {@code baseVersion}, so clients that never
     * send it keep last-write-wins.
     */
    public static AdminApiException versionConflict(String resource, int baseVersion, int currentVersion) {
        return new AdminApiException(HttpStatus.CONFLICT, "version_conflict",
                resource + " was edited from v" + baseVersion + " but v" + currentVersion
                        + " has been published since",
                null, currentVersion);
    }

    /** Throws {@link #versionConflict} when {@code baseVersion} is set and stale. */
    public static void requireBaseVersion(String resource, Integer baseVersion, Integer currentVersion) {
        if (baseVersion == null) return;
        int current = currentVersion == null ? 0 : currentVersion;
        if (current != baseVersion) throw versionConflict(resource, baseVersion, current);
    }

    public static AdminApiException badRequest(String code, String message) {
        return new AdminApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static AdminApiException notFound(String code, String message) {
        return new AdminApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static AdminApiException conflict(String code, String message) {
        return new AdminApiException(HttpStatus.CONFLICT, code, message);
    }

    public static AdminApiException forbidden(String code, String message) {
        return new AdminApiException(HttpStatus.FORBIDDEN, code, message);
    }
}
