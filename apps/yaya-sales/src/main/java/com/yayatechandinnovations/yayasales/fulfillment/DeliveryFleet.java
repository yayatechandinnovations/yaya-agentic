package com.yayatechandinnovations.yayasales.fulfillment;

import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids.AgentId;
import com.yayatechandinnovations.yayasales.platform.Ids.AssignmentId;
import com.yayatechandinnovations.yayasales.platform.Ids.ShipmentId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * §8.9/§17 — the in-house courier fleet: invite-gated self-registration,
 * KYC document review, merchant approval (which mints the courier credential),
 * assignment, delivery-status advance, and COD collection — the fleet is both
 * the delivery mechanism and the COD money path.
 *
 * <p>Trust (§15): registration needs a signed, unexpired, single-use store
 * invite; the phone is OTP-verified; documents are merchant-reviewed before
 * approval; a non-APPROVED agent can do nothing operational; an agent is
 * scoped to exactly one store and its own assignments. Tokens are opaque,
 * returned once, and stored only as SHA-256 ({@code credential_ref}).
 */
public interface DeliveryFleet {

    // ── invites & self-registration (§10.4 — the only public path in) ───────

    /** Admin: mint a single-use, expiring invite. The token is returned once. */
    Invite mintInvite(StoreId store, String createdBy, Duration ttl);

    /** Public, invite-gated: agent row PENDING + phone OTP issued. */
    Registration register(String inviteToken, AgentRegistration registration);

    /** Public: OTP check → phone verified + the registration-scoped token, returned once. */
    Credential verifyPhone(String phone, String otp);

    /** Courier (own id only): encrypted KYC uploads → DOCS_SUBMITTED / UNDER_REVIEW. */
    AgentView submitDocuments(AgentId agent, List<DocumentUpload> documents);

    // ── merchant review (§10.1) ──────────────────────────────────────────────

    List<AgentView> agentsOf(StoreId store, Optional<AgentStatus> status);

    Optional<AgentView> agent(AgentId id);

    /** Decrypt-on-demand for the operator's review — never returned in listings. */
    String documentContent(AgentId agent, String documentId);

    /**
     * KYC decision: requires phone verified + photo ID + a vehicle document
     * present (§14 courier trust), then mints the OPERATIONAL courier
     * credential — returned once, rotating out the registration token.
     */
    Credential approve(AgentId id, String reviewedBy);

    AgentView reject(AgentId id, String reviewedBy, String reason);

    AgentView suspend(AgentId id, String reviewedBy, String reason);

    // ── assignment & the courier's work (§10.4) ─────────────────────────────

    /** Explicit agent, or auto (any APPROVED agent of the store). Reassignment allowed. */
    AssignmentView assign(ShipmentId shipment, Optional<AgentId> agent, String actor);

    List<AssignmentView> assignmentsOf(AgentId agent);

    AssignmentView accept(AssignmentId id, AgentId agent);

    /** OUT_FOR_DELIVERY | DELIVERED | FAILED (+ proof) — the §8.7 manual advance. */
    AssignmentView updateStatus(AssignmentId id, AgentId agent, DeliveryStatus status,
                                Map<String, Object> proof);

    /**
     * COD collected at the door → settles the order's COD tender → order PAID
     * (§8.3). Idempotent: a retried collect settles exactly once (§14). The
     * amount must match the tender — a courier cannot under- or over-collect.
     */
    CodCollection collectCod(AssignmentId id, AgentId agent, long amountMinor);

    // ── shapes ───────────────────────────────────────────────────────────────

    enum AgentStatus { PENDING, DOCS_SUBMITTED, UNDER_REVIEW, APPROVED, REJECTED, SUSPENDED }

    enum DocKind { PHOTO_ID_FRONT, PHOTO_ID_BACK, DRIVER_LICENSE, VEHICLE_REGISTRATION, INSURANCE, OTHER }

    record Invite(String token, StoreId store, Instant expiresAt) {}

    record AgentRegistration(String legalName, Address legalAddress, String phone,
                             Vehicle vehicle, IdDocument idDoc) {}

    record Vehicle(String kind, String make, String model, String plate) {}

    record IdDocument(String type, String number) {}

    /** {@code otpEcho} is set ONLY when {@code yaya.sales.delivery.otp-echo-enabled} (dev/test). */
    record Registration(AgentId agent, StoreId store, AgentStatus status, String otpEcho) {}

    /** A bearer token, surfaced exactly once — only its SHA-256 is stored. */
    record Credential(AgentId agent, String token) {}

    record DocumentUpload(DocKind kind, String contentBase64) {}

    record DocumentView(String id, DocKind kind, String status, Instant uploadedAt) {}

    record AgentView(AgentId id, StoreId store, String legalName, Address legalAddress,
                     String phone, boolean phoneVerified, Vehicle vehicle, IdDocument idDoc,
                     AgentStatus status, List<DocumentView> documents, Instant registeredAt,
                     Instant approvedAt, String reviewedBy, String rejectReason) {}

    enum AssignmentStatus { ASSIGNED, ACCEPTED, COMPLETED, REASSIGNED }

    record AssignmentView(AssignmentId id, ShipmentId shipment, AgentId agent, StoreId store,
                          AssignmentStatus status, Instant assignedAt) {}

    record CodCollection(AssignmentId assignment, String orderId, long amountMinor,
                         String currency, boolean alreadyCollected) {}
}
