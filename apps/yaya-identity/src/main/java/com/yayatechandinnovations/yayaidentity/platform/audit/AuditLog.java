package com.yayatechandinnovations.yayaidentity.platform.audit;

/**
 * Append-only audit sink (design §14, PRD FR-6.1 / AC-40). Every authentication
 * event and authorization decision is recorded; the store never updates or
 * deletes (a DB trigger enforces immutability as a second line of defence).
 */
public interface AuditLog {

    void record(AuditEntry entry);
}
