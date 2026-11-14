package com.yayatechandinnovations.yayaagentic.usage;

/**
 * A metered profile's turn was refused because the tenant's monthly allowance
 * is used up. Raised before the model is called, so nothing ran and nothing
 * was counted. Callers turn it into their channel's refusal — a structured
 * {@code quota_exceeded} error on the sessions API, a plain sentence elsewhere
 * ({@link QuotaRefusals}).
 */
public class QuotaExceededException extends RuntimeException {

    public static final String CODE = "quota_exceeded";

    private final String tenantId;
    private final String month;
    private final long messages;
    private final int monthlyMessages;
    private volatile String language;

    public QuotaExceededException(String tenantId, String month, long messages, int monthlyMessages) {
        super("monthly allowance used up for tenant '" + tenantId + "' (" + month + ": "
                + messages + "/" + monthlyMessages + ")");
        this.tenantId = tenantId;
        this.month = month;
        this.messages = messages;
        this.monthlyMessages = monthlyMessages;
    }

    public String tenantId() { return tenantId; }
    public String month() { return month; }
    public long messages() { return messages; }
    public int monthlyMessages() { return monthlyMessages; }

    /** The refused profile's language, so a channel can word the refusal; may be null. */
    public String language() { return language; }

    public QuotaExceededException inLanguage(String language) {
        this.language = language;
        return this;
    }
}
