package com.yayatechandinnovations.yayaagentic.headless;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code headless_runs} table (V91). Every read and write names the
 * tenant, except the sweeps that are about age and state alone. The
 * principal token of a run has no column and never reaches this class.
 */
@Component
public class HeadlessRunStore {

    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";

    /** One row. {@code answer} is null unless completed and not yet erased. */
    public record Run(String id, String tenantId, String profileId, int profileVersion, String idemKey,
                      String principalSub, String status, String question, String answer,
                      String failureCode, String failureMessage, String usageJson, String toolsJson,
                      String contextJson, String sessionId, Instant createdAt, Instant startedAt,
                      Instant finishedAt) {}

    private static final String COLUMNS = """
            id, tenant_id, profile_id, profile_version, idem_key, principal_sub, status, question, answer,
            failure_code, failure_message, usage_json, tools_json, context_json, session_id,
            created_at, started_at, finished_at""";

    private static final RowMapper<Run> ROW = (rs, i) -> new Run(
            rs.getString("id"), rs.getString("tenant_id"), rs.getString("profile_id"),
            rs.getInt("profile_version"), rs.getString("idem_key"), rs.getString("principal_sub"),
            rs.getString("status"), rs.getString("question"), rs.getString("answer"),
            rs.getString("failure_code"), rs.getString("failure_message"), rs.getString("usage_json"),
            rs.getString("tools_json"), rs.getString("context_json"), rs.getString("session_id"),
            instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("started_at")),
            instant(rs.getTimestamp("finished_at")));

    private final JdbcTemplate jdbc;

    public HeadlessRunStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores a new queued run. False when the tenant already has a run under
     * {@code idemKey}: nothing was written.
     */
    public boolean insertQueued(String id, String tenantId, String profileId, int profileVersion, String idemKey,
                                String principalSub, String question, String contextJson, Instant now) {
        try {
            jdbc.update("""
                    INSERT INTO headless_runs (id, tenant_id, profile_id, profile_version, idem_key, principal_sub,
                                               status, question, context_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'queued', ?, ?, ?)
                    """, id, tenantId, profileId, profileVersion, idemKey, principalSub, question, contextJson,
                    Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<Run> find(String tenantId, String id) {
        List<Run> rows = jdbc.query("SELECT " + COLUMNS + " FROM headless_runs WHERE tenant_id = ? AND id = ?",
                ROW, tenantId, id);
        return rows.stream().findFirst();
    }

    public Optional<Run> findByIdemKey(String tenantId, String idemKey) {
        List<Run> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM headless_runs WHERE tenant_id = ? AND idem_key = ?",
                ROW, tenantId, idemKey);
        return rows.stream().findFirst();
    }

    /**
     * Frees the key of a run older than the idempotency window, so the same
     * key may name a new run. The old row keeps a key nobody can send.
     */
    public void releaseIdemKey(String tenantId, String id) {
        jdbc.update("UPDATE headless_runs SET idem_key = ? WHERE tenant_id = ? AND id = ?",
                "released:" + id, tenantId, id);
    }

    /** Runs of the tenant that are queued or running. */
    public int countInFlight(String tenantId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM headless_runs
                 WHERE tenant_id = ? AND status IN ('queued', 'running')
                """, Integer.class, tenantId);
        return n == null ? 0 : n;
    }

    /** A queued run that could not be handed to a worker: it never existed. */
    public void deleteQueued(String tenantId, String id) {
        jdbc.update("DELETE FROM headless_runs WHERE tenant_id = ? AND id = ? AND status = 'queued'",
                tenantId, id);
    }

    /** queued → running. False when the run is no longer queued (swept, or deleted). */
    public boolean markRunning(String tenantId, String id, Instant now) {
        return jdbc.update("""
                UPDATE headless_runs SET status = 'running', started_at = ?
                 WHERE tenant_id = ? AND id = ? AND status = 'queued'
                """, Timestamp.from(now), tenantId, id) == 1;
    }

    public void setSession(String tenantId, String id, String sessionId) {
        jdbc.update("UPDATE headless_runs SET session_id = ? WHERE tenant_id = ? AND id = ?",
                UUID.fromString(sessionId), tenantId, id);
    }

    /** running → completed. Only a running run finishes: one the start-up sweep closed stays closed. */
    public boolean complete(String tenantId, String id, String answer, String usageJson, String toolsJson,
                            Instant now) {
        return jdbc.update("""
                UPDATE headless_runs
                   SET status = 'completed', answer = ?, usage_json = ?, tools_json = ?, finished_at = ?
                 WHERE tenant_id = ? AND id = ? AND status = 'running'
                """, answer, usageJson, toolsJson, Timestamp.from(now), tenantId, id) == 1;
    }

    /** queued | running → failed. */
    public boolean fail(String tenantId, String id, String code, String message, String usageJson,
                        String toolsJson, Instant now) {
        return jdbc.update("""
                UPDATE headless_runs
                   SET status = 'failed', failure_code = ?, failure_message = ?, usage_json = ?, tools_json = ?,
                       finished_at = ?
                 WHERE tenant_id = ? AND id = ? AND status IN ('queued', 'running')
                """, code, message, usageJson, toolsJson, Timestamp.from(now), tenantId, id) == 1;
    }

    /**
     * Every run left queued or running by a process that is gone: its token
     * went with that process, so it cannot be run again. Returns how many.
     */
    public int failUnfinished(String code, String message, Instant now) {
        return jdbc.update("""
                UPDATE headless_runs SET status = 'failed', failure_code = ?, failure_message = ?, finished_at = ?
                 WHERE status IN ('queued', 'running')
                """, code, message, Timestamp.from(now));
    }

    /**
     * Runs still running that started before {@code runningBefore}, and still
     * queued that were created before {@code queuedBefore}: nothing is going
     * to finish them. One statement, on the same states {@link #complete},
     * {@link #fail} and {@link #markRunning} require — whichever of this and
     * a worker writes first, the other changes nothing. Returns how many.
     */
    public int failAbandoned(String code, String message, Instant runningBefore, Instant queuedBefore,
                             Instant now) {
        return jdbc.update("""
                UPDATE headless_runs SET status = 'failed', failure_code = ?, failure_message = ?, finished_at = ?
                 WHERE (status = 'running' AND started_at < ?)
                    OR (status = 'queued' AND created_at < ?)
                """, code, message, Timestamp.from(now), Timestamp.from(runningBefore),
                Timestamp.from(queuedBefore));
    }

    /** Erases the answers of runs created before {@code cutoff}. Returns how many. */
    public int eraseAnswersBefore(Instant cutoff) {
        return jdbc.update("""
                UPDATE headless_runs SET answer = NULL
                 WHERE status = 'completed' AND answer IS NOT NULL AND created_at < ?
                """, Timestamp.from(cutoff));
    }

    /** Deletes finished runs created before {@code cutoff}. Returns how many. */
    public int deleteBefore(Instant cutoff) {
        return jdbc.update("""
                DELETE FROM headless_runs
                 WHERE status IN ('completed', 'failed') AND created_at < ?
                """, Timestamp.from(cutoff));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
