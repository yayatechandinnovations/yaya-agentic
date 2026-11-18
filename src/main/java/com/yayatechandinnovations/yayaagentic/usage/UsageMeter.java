package com.yayatechandinnovations.yayaagentic.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Counts conversation turns and model tokens per tenant, profile and month, and
 * enforces the tenant's monthly allowance on metered profiles.
 *
 * <p><b>What is metered.</b> Only profiles flagged {@code metered} (staff/owner
 * agents). Their turns draw on {@code tenant_usage_quotas.monthly_messages};
 * an unmetered profile (customer-facing chat) is counted for reporting and
 * never refused.
 *
 * <p><b>Atomicity.</b> Admission is one conditional upsert on the tenant's
 * single month row — the row lock makes "is there room?" and "take one" the
 * same step, so concurrent turns can never admit more than the allowance. A
 * turn gives its message back only when the model was never asked, or the
 * provider failed without producing anything; a turn the client walked away
 * from after the model was called stays counted.
 *
 * <p><b>Tokens</b> are recorded whenever the provider reported them, whether or
 * not the turn counted as a message — including the opening a start flow has
 * the model write, which is never a message ({@link #recordTokens}).
 *
 * <p><b>Month.</b> The calendar month in the tenant's time zone —
 * {@code settings.timeZone} on the tenant (an IANA id) — or UTC when the tenant
 * has none or it does not parse.
 *
 * <p><b>Media.</b> {@code voice_seconds} (audio transcribed) and {@code images_in}
 * (images given to the model) are the tenant's own media spend and count for
 * every profile, metered or not; the voice allowance
 * ({@code yaya.agentic.voice.monthly-seconds}, null = unlimited) refuses new
 * voice notes once the month's seconds are used (tracker contract C).
 *
 * <p>Counting must never break a conversation: a storage failure is logged and
 * the turn proceeds unmetered.
 */
@Component
public class UsageMeter {

    private static final Logger LOG = LoggerFactory.getLogger(UsageMeter.class);
    private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Integer voiceMonthlySeconds;

    public UsageMeter(JdbcTemplate jdbc, Clock clock) {
        this(jdbc, clock, "3600");
    }

    @Autowired
    public UsageMeter(JdbcTemplate jdbc, Clock clock,
                      @Value("${yaya.agentic.voice.monthly-seconds:3600}") String voiceMonthlySeconds) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.voiceMonthlySeconds = parseAllowance(voiceMonthlySeconds);
    }

    /** Blank, {@code null}, {@code unlimited} or a negative number = unlimited. */
    static Integer parseAllowance(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.equals("null") || v.equals("unlimited") || v.equals("none")) return null;
        try {
            int n = Integer.parseInt(v);
            return n < 0 ? null : n;
        } catch (NumberFormatException e) {
            LOG.warn("yaya.agentic.voice.monthly-seconds '{}' is not a number; voice is unlimited", raw);
            return null;
        }
    }

    /** One admitted turn. {@code reserved} = a message was taken from the tenant's month row. */
    public record Reservation(String tenantId, String profileId, String month, boolean metered, boolean reserved) {}

    /**
     * Model tokens of one turn or one call. {@code input} is the whole prompt;
     * {@code cacheRead} and {@code cacheCreation} are the parts of it the
     * provider read from / wrote to its prompt cache.
     */
    public record Tokens(long input, long output, long cacheRead, long cacheCreation) {
        public Tokens {
            input = Math.max(0, input);
            output = Math.max(0, output);
            cacheRead = Math.max(0, cacheRead);
            cacheCreation = Math.max(0, cacheCreation);
        }

        public Tokens(long input, long output) {
            this(input, output, 0, 0);
        }

        boolean none() {
            return input == 0 && output == 0;
        }
    }

    /**
     * The tenant's metered totals for a month, next to its allowance (null =
     * unlimited). {@code cacheReadTokens} / {@code cacheCreationTokens} are
     * included in {@code inputTokens}.
     */
    public record Totals(String month, long messages, long inputTokens, long outputTokens,
                         long cacheReadTokens, long cacheCreationTokens, Integer monthlyMessages,
                         long voiceSeconds, long imagesIn) {
        public Totals(String month, long messages, long inputTokens, long outputTokens,
                      long cacheReadTokens, long cacheCreationTokens, Integer monthlyMessages) {
            this(month, messages, inputTokens, outputTokens, cacheReadTokens, cacheCreationTokens,
                    monthlyMessages, 0, 0);
        }
    }

    public record ProfileTotals(String profileId, boolean metered, long messages, long inputTokens,
                                long outputTokens, long cacheReadTokens, long cacheCreationTokens,
                                long voiceSeconds, long imagesIn) {
        public ProfileTotals(String profileId, boolean metered, long messages, long inputTokens,
                             long outputTokens, long cacheReadTokens, long cacheCreationTokens) {
            this(profileId, metered, messages, inputTokens, outputTokens, cacheReadTokens,
                    cacheCreationTokens, 0, 0);
        }
    }

    /**
     * Admit one turn. For a metered profile this takes one message from the
     * month's allowance, or throws {@link QuotaExceededException} when none is
     * left — before anything ran.
     */
    public Reservation admit(String tenantId, String profileId, boolean metered) {
        String month;
        Integer quota;
        try {
            TenantRow row = tenantRow(tenantId);
            month = month(row.zone());
            quota = row.monthlyMessages();
        } catch (DataAccessException e) {
            LOG.warn("usage admission unavailable for tenant {} ({}); turn proceeds unmetered",
                    tenantId, e.getClass().getSimpleName());
            return new Reservation(tenantId, profileId, month(ZoneOffset.UTC), metered, false);
        }
        if (!metered) return new Reservation(tenantId, profileId, month, false, false);

        int taken;
        try {
            if (quota == null) {
                taken = jdbc.update("""
                        INSERT INTO tenant_usage_monthly (tenant_id, month, messages) VALUES (?, ?, 1)
                        ON CONFLICT (tenant_id, month) DO UPDATE
                           SET messages = tenant_usage_monthly.messages + 1, updated_at = NOW()
                        """, tenantId, month);
            } else if (quota <= 0) {
                taken = 0;
            } else {
                taken = jdbc.update("""
                        INSERT INTO tenant_usage_monthly (tenant_id, month, messages) VALUES (?, ?, 1)
                        ON CONFLICT (tenant_id, month) DO UPDATE
                           SET messages = tenant_usage_monthly.messages + 1, updated_at = NOW()
                         WHERE tenant_usage_monthly.messages < ?
                        """, tenantId, month, quota);
            }
        } catch (DataAccessException e) {
            LOG.warn("usage admission failed for tenant {} ({}); turn proceeds unmetered",
                    tenantId, e.getClass().getSimpleName());
            return new Reservation(tenantId, profileId, month, true, false);
        }
        if (taken == 0) {
            throw new QuotaExceededException(tenantId, month, messagesUsed(tenantId, month), quota);
        }
        return new Reservation(tenantId, profileId, month, true, true);
    }

    /**
     * Close an admitted turn. {@code counted} = the model was called for it
     * (and did not simply fail with nothing produced). A turn that is not
     * counted returns its reserved message. Tokens are recorded either way.
     */
    public void settle(Reservation r, boolean counted, long inputTokens, long outputTokens) {
        settle(r, counted, new Tokens(inputTokens, outputTokens));
    }

    public void settle(Reservation r, boolean counted, Tokens t) {
        if (r == null) return;
        try {
            if (!counted) {
                if (r.reserved()) {
                    jdbc.update("""
                            UPDATE tenant_usage_monthly SET messages = messages - 1, updated_at = NOW()
                             WHERE tenant_id = ? AND month = ? AND messages > 0
                            """, r.tenantId(), r.month());
                }
                addTokens(r.tenantId(), r.profileId(), r.month(), r.metered(), t);
                return;
            }
            if (r.metered()) {
                if (r.reserved()) {
                    jdbc.update("""
                            UPDATE tenant_usage_monthly
                               SET input_tokens = input_tokens + ?, output_tokens = output_tokens + ?,
                                   cache_read_tokens = cache_read_tokens + ?,
                                   cache_creation_tokens = cache_creation_tokens + ?, updated_at = NOW()
                             WHERE tenant_id = ? AND month = ?
                            """, t.input(), t.output(), t.cacheRead(), t.cacheCreation(), r.tenantId(), r.month());
                } else {
                    // Admission could not reach storage; count the turn now.
                    jdbc.update("""
                            INSERT INTO tenant_usage_monthly (tenant_id, month, messages, input_tokens, output_tokens,
                                                              cache_read_tokens, cache_creation_tokens)
                            VALUES (?, ?, 1, ?, ?, ?, ?)
                            ON CONFLICT (tenant_id, month) DO UPDATE
                               SET messages = tenant_usage_monthly.messages + 1,
                                   input_tokens = tenant_usage_monthly.input_tokens + EXCLUDED.input_tokens,
                                   output_tokens = tenant_usage_monthly.output_tokens + EXCLUDED.output_tokens,
                                   cache_read_tokens = tenant_usage_monthly.cache_read_tokens + EXCLUDED.cache_read_tokens,
                                   cache_creation_tokens = tenant_usage_monthly.cache_creation_tokens
                                                           + EXCLUDED.cache_creation_tokens,
                                   updated_at = NOW()
                            """, r.tenantId(), r.month(), t.input(), t.output(), t.cacheRead(), t.cacheCreation());
                }
            }
            jdbc.update("""
                    INSERT INTO profile_usage_monthly
                           (tenant_id, profile_id, month, metered, messages, input_tokens, output_tokens,
                            cache_read_tokens, cache_creation_tokens)
                    VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, profile_id, month) DO UPDATE
                       SET messages = profile_usage_monthly.messages + 1,
                           input_tokens = profile_usage_monthly.input_tokens + EXCLUDED.input_tokens,
                           output_tokens = profile_usage_monthly.output_tokens + EXCLUDED.output_tokens,
                           cache_read_tokens = profile_usage_monthly.cache_read_tokens + EXCLUDED.cache_read_tokens,
                           cache_creation_tokens = profile_usage_monthly.cache_creation_tokens
                                                   + EXCLUDED.cache_creation_tokens,
                           metered = EXCLUDED.metered,
                           updated_at = NOW()
                    """, r.tenantId(), r.profileId(), r.month(), r.metered(),
                    t.input(), t.output(), t.cacheRead(), t.cacheCreation());
        } catch (DataAccessException e) {
            LOG.warn("usage settle failed for tenant {} profile {} ({})",
                    r.tenantId(), r.profileId(), e.getClass().getSimpleName());
        }
    }

    /**
     * Record model tokens that belong to no user message — the opening a start
     * flow has the model write. Never changes a message count.
     */
    public void recordTokens(String tenantId, String profileId, boolean metered,
                             long inputTokens, long outputTokens) {
        recordTokens(tenantId, profileId, metered, new Tokens(inputTokens, outputTokens));
    }

    public void recordTokens(String tenantId, String profileId, boolean metered, Tokens tokens) {
        try {
            addTokens(tenantId, profileId, month(tenantRow(tenantId).zone()), metered, tokens);
        } catch (DataAccessException e) {
            LOG.warn("usage tokens not recorded for tenant {} profile {} ({})",
                    tenantId, profileId, e.getClass().getSimpleName());
        }
    }

    /**
     * True while a metered turn of this tenant would still be admitted. Reads
     * only — nothing is taken. Unknown (storage unavailable) reads as true,
     * like admission.
     */
    public boolean hasAllowance(String tenantId) {
        try {
            TenantRow row = tenantRow(tenantId);
            if (row.monthlyMessages() == null) return true;
            return messagesUsed(tenantId, month(row.zone())) < row.monthlyMessages();
        } catch (DataAccessException e) {
            return true;
        }
    }

    private void addTokens(String tenantId, String profileId, String month, boolean metered, Tokens t) {
        if (t == null || t.none()) return;
        if (metered) {
            jdbc.update("""
                    INSERT INTO tenant_usage_monthly (tenant_id, month, messages, input_tokens, output_tokens,
                                                      cache_read_tokens, cache_creation_tokens)
                    VALUES (?, ?, 0, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, month) DO UPDATE
                       SET input_tokens = tenant_usage_monthly.input_tokens + EXCLUDED.input_tokens,
                           output_tokens = tenant_usage_monthly.output_tokens + EXCLUDED.output_tokens,
                           cache_read_tokens = tenant_usage_monthly.cache_read_tokens + EXCLUDED.cache_read_tokens,
                           cache_creation_tokens = tenant_usage_monthly.cache_creation_tokens
                                                   + EXCLUDED.cache_creation_tokens,
                           updated_at = NOW()
                    """, tenantId, month, t.input(), t.output(), t.cacheRead(), t.cacheCreation());
        }
        jdbc.update("""
                INSERT INTO profile_usage_monthly
                       (tenant_id, profile_id, month, metered, messages, input_tokens, output_tokens,
                        cache_read_tokens, cache_creation_tokens)
                VALUES (?, ?, ?, ?, 0, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, profile_id, month) DO UPDATE
                   SET input_tokens = profile_usage_monthly.input_tokens + EXCLUDED.input_tokens,
                       output_tokens = profile_usage_monthly.output_tokens + EXCLUDED.output_tokens,
                       cache_read_tokens = profile_usage_monthly.cache_read_tokens + EXCLUDED.cache_read_tokens,
                       cache_creation_tokens = profile_usage_monthly.cache_creation_tokens
                                               + EXCLUDED.cache_creation_tokens,
                       metered = EXCLUDED.metered,
                       updated_at = NOW()
                """, tenantId, profileId, month, metered, t.input(), t.output(), t.cacheRead(), t.cacheCreation());
    }

    /** The tenant's metered totals. {@code month} null = the current month in the tenant's zone. */
    public Totals usage(String tenantId, String month) {
        TenantRow row = tenantRow(tenantId);
        String resolved = month == null || month.isBlank() ? month(row.zone()) : requireMonth(month);
        List<Totals> rows = jdbc.query("""
                SELECT messages, input_tokens, output_tokens, cache_read_tokens, cache_creation_tokens,
                       voice_seconds, images_in
                  FROM tenant_usage_monthly
                 WHERE tenant_id = ? AND month = ?
                """, (rs, i) -> new Totals(resolved, rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), row.monthlyMessages(), rs.getLong(6), rs.getLong(7)),
                tenantId, resolved);
        return rows.isEmpty() ? new Totals(resolved, 0, 0, 0, 0, 0, row.monthlyMessages()) : rows.get(0);
    }

    public List<ProfileTotals> usageByProfile(String tenantId, String month) {
        return jdbc.query("""
                SELECT profile_id, metered, messages, input_tokens, output_tokens,
                       cache_read_tokens, cache_creation_tokens, voice_seconds, images_in
                  FROM profile_usage_monthly
                 WHERE tenant_id = ? AND month = ? ORDER BY profile_id
                """, (rs, i) -> new ProfileTotals(rs.getString(1), rs.getBoolean(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9)),
                tenantId, requireMonth(month));
    }

    /* ------------------------------- media -------------------------------- */

    /** The month's voice allowance in seconds; null = unlimited. */
    public Integer voiceMonthlySeconds() {
        return voiceMonthlySeconds;
    }

    /** Seconds of audio transcribed this month for the tenant (every profile). */
    public long voiceSecondsUsed(String tenantId) {
        try {
            String month = month(tenantRow(tenantId).zone());
            List<Long> rows = jdbc.query(
                    "SELECT voice_seconds FROM tenant_usage_monthly WHERE tenant_id = ? AND month = ?",
                    (rs, i) -> rs.getLong(1), tenantId, month);
            return rows.isEmpty() ? 0 : rows.get(0);
        } catch (DataAccessException e) {
            return 0;
        }
    }

    /** True while another voice note may be transcribed this month. Unknown reads as true. */
    public boolean hasVoiceAllowance(String tenantId) {
        if (voiceMonthlySeconds == null) return true;
        return voiceSecondsUsed(tenantId) < voiceMonthlySeconds;
    }

    /** Audio transcribed: counted on the tenant's month row and the profile's (when known). */
    public void recordVoiceSeconds(String tenantId, String profileId, long seconds) {
        recordMedia(tenantId, profileId, Math.max(0, seconds), 0);
    }

    /** Images handed to the model as vision input. */
    public void recordImagesIn(String tenantId, String profileId, long images) {
        recordMedia(tenantId, profileId, 0, Math.max(0, images));
    }

    private void recordMedia(String tenantId, String profileId, long seconds, long images) {
        if (seconds == 0 && images == 0) return;
        try {
            String month = month(tenantRow(tenantId).zone());
            jdbc.update("""
                    INSERT INTO tenant_usage_monthly (tenant_id, month, messages, voice_seconds, images_in)
                    VALUES (?, ?, 0, ?, ?)
                    ON CONFLICT (tenant_id, month) DO UPDATE
                       SET voice_seconds = tenant_usage_monthly.voice_seconds + EXCLUDED.voice_seconds,
                           images_in = tenant_usage_monthly.images_in + EXCLUDED.images_in,
                           updated_at = NOW()
                    """, tenantId, month, seconds, images);
            if (profileId != null && !profileId.isBlank()) {
                jdbc.update("""
                        INSERT INTO profile_usage_monthly
                               (tenant_id, profile_id, month, metered, messages, voice_seconds, images_in)
                        VALUES (?, ?, ?, FALSE, 0, ?, ?)
                        ON CONFLICT (tenant_id, profile_id, month) DO UPDATE
                           SET voice_seconds = profile_usage_monthly.voice_seconds + EXCLUDED.voice_seconds,
                               images_in = profile_usage_monthly.images_in + EXCLUDED.images_in,
                               updated_at = NOW()
                        """, tenantId, profileId, month, seconds, images);
            }
        } catch (DataAccessException e) {
            LOG.warn("usage media not recorded for tenant {} profile {} ({})",
                    tenantId, profileId, e.getClass().getSimpleName());
        }
    }

    /** Null = unlimited. */
    public void setMonthlyMessages(String tenantId, Integer monthlyMessages) {
        if (monthlyMessages != null && monthlyMessages < 0) {
            throw new IllegalArgumentException("monthlyMessages must be zero or more, or null for unlimited");
        }
        jdbc.update("""
                INSERT INTO tenant_usage_quotas (tenant_id, monthly_messages) VALUES (?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET monthly_messages = EXCLUDED.monthly_messages, updated_at = NOW()
                """, tenantId, monthlyMessages);
    }

    public static String requireMonth(String month) {
        if (month == null || !MONTH.matcher(month).matches()) {
            throw new IllegalArgumentException("month must be YYYY-MM");
        }
        return month;
    }

    // ------------------------------------------------------------------

    private record TenantRow(ZoneId zone, Integer monthlyMessages) {}

    private TenantRow tenantRow(String tenantId) {
        List<TenantRow> rows = jdbc.query("""
                SELECT t.settings_json ->> 'timeZone', q.monthly_messages
                  FROM tenants t LEFT JOIN tenant_usage_quotas q ON q.tenant_id = t.id
                 WHERE t.id = ?
                """, (rs, i) -> {
            ZoneId zone = zoneOf(rs.getString(1));
            int quota = rs.getInt(2);
            return new TenantRow(zone, rs.wasNull() ? null : quota);
        }, tenantId);
        return rows.isEmpty() ? new TenantRow(ZoneOffset.UTC, null) : rows.get(0);
    }

    private long messagesUsed(String tenantId, String month) {
        try {
            List<Long> rows = jdbc.query(
                    "SELECT messages FROM tenant_usage_monthly WHERE tenant_id = ? AND month = ?",
                    (rs, i) -> rs.getLong(1), tenantId, month);
            return rows.isEmpty() ? 0 : rows.get(0);
        } catch (DataAccessException e) {
            return 0;
        }
    }

    private static ZoneId zoneOf(String raw) {
        if (raw == null || raw.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(raw.trim());
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    private String month(ZoneId zone) {
        return YearMonth.now(clock.withZone(zone)).toString();
    }
}
