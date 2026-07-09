package com.yayatechandinnovations.yayasales.fulfillment.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryFleet;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryMethodKind;
import com.yayatechandinnovations.yayasales.fulfillment.DeliveryStatus;
import com.yayatechandinnovations.yayasales.fulfillment.Shipments;
import com.yayatechandinnovations.yayasales.payments.TenderKind;
import com.yayatechandinnovations.yayasales.payments.TenderService;
import com.yayatechandinnovations.yayasales.payments.TenderView;
import com.yayatechandinnovations.yayasales.platform.Address;
import com.yayatechandinnovations.yayasales.platform.Ids;
import com.yayatechandinnovations.yayasales.platform.Ids.AgentId;
import com.yayatechandinnovations.yayasales.platform.Ids.AssignmentId;
import com.yayatechandinnovations.yayasales.platform.Ids.OrderId;
import com.yayatechandinnovations.yayasales.platform.Ids.ShipmentId;
import com.yayatechandinnovations.yayasales.platform.Ids.StoreId;
import com.yayatechandinnovations.yayasales.platform.auth.Caller;
import com.yayatechandinnovations.yayasales.platform.auth.CourierTokenVerifier;
import com.yayatechandinnovations.yayasales.platform.crypto.SecretCipher;
import com.yayatechandinnovations.yayasales.platform.outbox.Outbox;
import com.yayatechandinnovations.yayasales.platform.web.ApiErrors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * §8.9 — the fleet's whole life: invite → register (PENDING) → OTP-verified
 * phone (registration token) → encrypted KYC uploads → merchant approval
 * (mints + ROTATES to the operational credential) → assignment → status
 * advances with proof → COD collection (the §8.3 capture path).
 *
 * <p>Also the {@link CourierTokenVerifier}: SHA-256 of the presented bearer →
 * {@code credential_ref}. Any registered agent authenticates (so a pending
 * courier can upload documents and read its own status); the OPERATIONAL
 * endpoints demand {@link AgentStatus#APPROVED} via {@link #requireApproved} —
 * that is the §14 KYC gate.
 */
@Service
public class DefaultDeliveryFleet implements DeliveryFleet, CourierTokenVerifier {

    private static final Set<DocKind> PHOTO_ID = EnumSet.of(DocKind.PHOTO_ID_FRONT, DocKind.DRIVER_LICENSE);
    private static final Set<DocKind> VEHICLE_DOC = EnumSet.of(DocKind.VEHICLE_REGISTRATION, DocKind.INSURANCE);

    private final DeliveryAgentRepository agents;
    private final DeliveryAgentInviteRepository invites;
    private final DeliveryAgentDocumentRepository documents;
    private final DeliveryAssignmentRepository assignments;
    private final ShipmentRepository shipments;
    private final DefaultShipments shipmentService;
    private final TenderService tenders;
    private final SecretCipher cipher;
    private final Outbox outbox;
    private final SalesDeliveryProperties properties;
    private final ObjectMapper json;

    public DefaultDeliveryFleet(DeliveryAgentRepository agents,
                                DeliveryAgentInviteRepository invites,
                                DeliveryAgentDocumentRepository documents,
                                DeliveryAssignmentRepository assignments,
                                ShipmentRepository shipments,
                                DefaultShipments shipmentService,
                                TenderService tenders,
                                SecretCipher cipher,
                                Outbox outbox,
                                SalesDeliveryProperties properties,
                                ObjectMapper json) {
        this.agents = agents;
        this.invites = invites;
        this.documents = documents;
        this.assignments = assignments;
        this.shipments = shipments;
        this.shipmentService = shipmentService;
        this.tenders = tenders;
        this.cipher = cipher;
        this.outbox = outbox;
        this.properties = properties;
        this.json = json;
    }

    // ── courier auth (the filter's verifier) ─────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Optional<Caller.Courier> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return agents.findByCredentialRef(Tokens.sha256(token))
                .map(a -> new Caller.Courier(new AgentId(a.getId()), new StoreId(a.getStoreId())));
    }

    /** The §14 KYC gate: only APPROVED agents act. 403, no detail leaked. */
    AgentView requireApproved(AgentId id) {
        AgentView agent = agent(id).orElseThrow(
                () -> new ApiErrors.NotFoundException("delivery agent", id.value()));
        if (agent.status() != AgentStatus.APPROVED) {
            throw new CourierNotApprovedException();
        }
        return agent;
    }

    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.FORBIDDEN)
    public static class CourierNotApprovedException extends RuntimeException {
        CourierNotApprovedException() {
            super("courier is not approved for deliveries");
        }
    }

    // ── invites & registration (§8.9 flow steps 1–3) ─────────────────────────

    @Override
    @Transactional
    public Invite mintInvite(StoreId store, String createdBy, Duration ttl) {
        String token = Tokens.newToken();
        Duration effective = ttl != null ? ttl : Duration.ofHours(properties.inviteTtl());
        Instant expiresAt = Instant.now().plus(effective);
        invites.save(new DeliveryAgentInviteEntity(Tokens.sha256(token), store.value(),
                createdBy, expiresAt));
        return new Invite(token, store, expiresAt);
    }

    @Override
    @Transactional
    public Registration register(String inviteToken, AgentRegistration registration) {
        if (inviteToken == null || inviteToken.isBlank()) {
            throw new ApiErrors.UnprocessableException("an invite token is required");
        }
        // Bad, expired and used invites all answer identically — no probing.
        DeliveryAgentInviteEntity invite = invites.lockByTokenHash(Tokens.sha256(inviteToken))
                .filter(i -> i.usable(Instant.now()))
                .orElseThrow(() -> new ApiErrors.UnprocessableException(
                        "this registration invite is not valid"));
        require(registration.legalName(), "legalName");
        require(registration.phone(), "phone");
        if (registration.legalAddress() == null || registration.vehicle() == null) {
            throw new ApiErrors.UnprocessableException(
                    "legalAddress and vehicle are required — courier registration is KYC-grade");
        }

        String otp = Tokens.newOtp();
        DeliveryAgentEntity agent = new DeliveryAgentEntity(Ids.newId(), invite.getStoreId(),
                registration.legalName().trim(), write(registration.legalAddress()),
                registration.phone().trim(),
                registration.idDoc() == null ? null : write(registration.idDoc()),
                write(registration.vehicle()),
                Tokens.sha256(otp), Instant.now().plus(Duration.ofMinutes(properties.otpTtl())));
        agents.save(agent);
        invite.markUsed(Instant.now());

        outbox.append(new StoreId(invite.getStoreId()), "delivery_agent", agent.getId(),
                "delivery_agent.registered", "{\"agentId\":\"" + agent.getId()
                        + "\",\"legalName\":" + quote(agent.getLegalName()) + "}");
        return new Registration(new AgentId(agent.getId()), new StoreId(agent.getStoreId()),
                AgentStatus.PENDING, properties.otpEcho() ? otp : null);
    }

    @Override
    @Transactional
    public Credential verifyPhone(String phone, String otp) {
        if (phone == null || otp == null) {
            throw new ApiErrors.UnprocessableException("phone and otp are required");
        }
        Instant now = Instant.now();
        DeliveryAgentEntity agent = agents.findByPhone(phone.trim()).stream()
                .filter(a -> !a.isPhoneVerified())
                .filter(a -> a.getPhoneOtpHash() != null
                        && a.getPhoneOtpHash().equals(Tokens.sha256(otp))
                        && a.getPhoneOtpExpiresAt() != null
                        && a.getPhoneOtpExpiresAt().isAfter(now))
                .findFirst()
                .orElseThrow(() -> new ApiErrors.UnprocessableException(
                        "the verification code is not valid"));

        agent.markPhoneVerified();
        // Registration-scoped token: enough to upload documents and read /me;
        // approval ROTATES it to the operational credential (§10.4).
        String token = Tokens.newToken();
        agent.setCredentialRef(Tokens.sha256(token));
        return new Credential(new AgentId(agent.getId()), token);
    }

    @Override
    @Transactional
    public AgentView submitDocuments(AgentId id, List<DocumentUpload> uploads) {
        DeliveryAgentEntity agent = locked(id);
        if (!agent.isPhoneVerified()) {
            throw new ApiErrors.UnprocessableException("verify the phone before uploading documents");
        }
        switch (agent.status()) {
            case PENDING, DOCS_SUBMITTED, UNDER_REVIEW -> { }
            default -> throw new ApiErrors.ConflictException(
                    "agent is " + agent.status() + " — documents are closed");
        }
        if (uploads == null || uploads.isEmpty()) {
            throw new ApiErrors.UnprocessableException("at least one document is required");
        }

        for (DocumentUpload upload : uploads) {
            if (upload.kind() == null || upload.contentBase64() == null
                    || upload.contentBase64().isBlank()) {
                throw new ApiErrors.UnprocessableException("every document needs a kind and content");
            }
            // PII at rest = ciphertext only (§15). No master key → fail, never store raw.
            SecretCipher.Encrypted encrypted = cipher.encrypt(upload.contentBase64());
            documents.save(new DeliveryAgentDocumentEntity(Ids.newId(), agent.getId(),
                    upload.kind().name(),
                    "{\"ciphertext\":" + quote(encrypted.ciphertext())
                            + ",\"iv\":" + quote(encrypted.iv()) + "}"));
        }

        agent.setStatus(requiredDocsPresent(agent.getId())
                ? AgentStatus.UNDER_REVIEW : AgentStatus.DOCS_SUBMITTED);
        return toView(agent);
    }

    // ── merchant review (§10.1) ──────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<AgentView> agentsOf(StoreId store, Optional<AgentStatus> status) {
        List<DeliveryAgentEntity> rows = status
                .map(s -> agents.findByStoreIdAndStatusOrderByRegisteredAtDesc(store.value(), s.name()))
                .orElseGet(() -> agents.findByStoreIdOrderByRegisteredAtDesc(store.value()));
        return rows.stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentView> agent(AgentId id) {
        return agents.findById(id.value()).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public String documentContent(AgentId agent, String documentId) {
        DeliveryAgentDocumentEntity doc = documents.findById(documentId)
                .filter(d -> d.getAgentId().equals(agent.value()))
                .orElseThrow(() -> new ApiErrors.NotFoundException("document", documentId));
        Map<String, Object> envelope = readMap(doc.getBlobRef());
        return cipher.decrypt(String.valueOf(envelope.get("ciphertext")),
                String.valueOf(envelope.get("iv")));
    }

    @Override
    @Transactional
    public Credential approve(AgentId id, String reviewedBy) {
        DeliveryAgentEntity agent = locked(id);
        switch (agent.status()) {
            case DOCS_SUBMITTED, UNDER_REVIEW, SUSPENDED -> { }
            case APPROVED -> throw new ApiErrors.ConflictException("agent is already approved");
            default -> throw new ApiErrors.UnprocessableException(
                    "agent is " + agent.status() + " — nothing to approve yet");
        }
        if (!agent.isPhoneVerified()) {
            throw new ApiErrors.UnprocessableException("phone is not verified");
        }
        // §14 courier trust: approval REQUIRES the reviewed documents.
        if (!requiredDocsPresent(agent.getId())) {
            throw new ApiErrors.UnprocessableException(
                    "approval requires a photo ID and a vehicle document (registration or insurance)");
        }

        agent.approve(reviewedBy, Instant.now());
        // Mint the operational credential — returned once; the registration
        // token dies with this rotation.
        String token = Tokens.newToken();
        agent.setCredentialRef(Tokens.sha256(token));
        return new Credential(new AgentId(agent.getId()), token);
    }

    @Override
    @Transactional
    public AgentView reject(AgentId id, String reviewedBy, String reason) {
        DeliveryAgentEntity agent = locked(id);
        agent.decide(AgentStatus.REJECTED, reviewedBy, reason);
        return toView(agent);
    }

    @Override
    @Transactional
    public AgentView suspend(AgentId id, String reviewedBy, String reason) {
        DeliveryAgentEntity agent = locked(id);
        agent.decide(AgentStatus.SUSPENDED, reviewedBy, reason);
        return toView(agent);
    }

    // ── assignment (§8.9) ────────────────────────────────────────────────────

    @Override
    @Transactional
    public AssignmentView assign(ShipmentId shipmentId, Optional<AgentId> explicitAgent, String actor) {
        ShipmentEntity shipment = shipments.lockById(shipmentId.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("shipment", shipmentId.value()));
        if (!DeliveryMethodKind.LOCAL_DELIVERY.name().equals(shipment.getMethodKind())) {
            throw new ApiErrors.UnprocessableException(
                    "only LOCAL_DELIVERY shipments are assigned to the fleet");
        }
        if (shipment.status() != Shipments.Status.DISPATCHED) {
            throw new ApiErrors.UnprocessableException("shipment must be dispatched before assignment");
        }

        DeliveryAgentEntity agent = explicitAgent
                .map(id -> agents.findById(id.value())
                        .filter(a -> a.getStoreId().equals(shipment.getStoreId()))
                        .orElseThrow(() -> new ApiErrors.NotFoundException(
                                "delivery agent", id.value())))
                .orElseGet(() -> agents.findByStoreIdAndStatusOrderByRegisteredAtDesc(
                                shipment.getStoreId(), AgentStatus.APPROVED.name())
                        .stream().findFirst()
                        .orElseThrow(() -> new ApiErrors.UnprocessableException(
                                "the store has no approved delivery agents")));
        if (agent.status() != AgentStatus.APPROVED) {
            throw new ApiErrors.UnprocessableException("agent is not approved for deliveries");
        }

        // Reassignment is an event, not an edit (§8.9).
        assignments.findByShipmentIdOrderByAssignedAtDesc(shipment.getId()).stream()
                .filter(DeliveryAssignmentEntity::active)
                .forEach(a -> a.setStatus(AssignmentStatus.REASSIGNED));

        DeliveryAssignmentEntity assignment = new DeliveryAssignmentEntity(Ids.newId(),
                shipment.getId(), agent.getId(), shipment.getStoreId());
        assignments.save(assignment);
        outbox.append(new StoreId(shipment.getStoreId()), "delivery_assignment", assignment.getId(),
                "delivery.assigned", "{\"assignmentId\":\"" + assignment.getId()
                        + "\",\"shipmentId\":\"" + shipment.getId()
                        + "\",\"agentId\":\"" + agent.getId() + "\"}");
        return toView(assignment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssignmentView> assignmentsOf(AgentId agent) {
        return assignments.findByAgentIdOrderByAssignedAtDesc(agent.value()).stream()
                .map(this::toView).toList();
    }

    @Override
    @Transactional
    public AssignmentView accept(AssignmentId id, AgentId agent) {
        DeliveryAssignmentEntity assignment = owned(id, agent);
        if (assignment.status() == AssignmentStatus.ASSIGNED) {
            assignment.setStatus(AssignmentStatus.ACCEPTED);
        }
        return toView(assignment);
    }

    @Override
    @Transactional
    public AssignmentView updateStatus(AssignmentId id, AgentId agent, DeliveryStatus status,
                                       Map<String, Object> proof) {
        if (status != DeliveryStatus.OUT_FOR_DELIVERY && status != DeliveryStatus.DELIVERED
                && status != DeliveryStatus.FAILED) {
            throw new ApiErrors.UnprocessableException(
                    "a courier reports OUT_FOR_DELIVERY, DELIVERED or FAILED");
        }
        DeliveryAssignmentEntity assignment = owned(id, agent);
        if (!assignment.active()) {
            throw new ApiErrors.ConflictException("assignment is " + assignment.getStatus());
        }
        shipmentService.advanceDelivery(new ShipmentId(assignment.getShipmentId()), status, proof,
                "courier update", null, Instant.now(), "courier:" + agent.value());
        if (status == DeliveryStatus.DELIVERED) {
            assignment.setStatus(AssignmentStatus.COMPLETED);
        }
        return toView(assignment);
    }

    // ── COD collection (§8.3/§8.9 — the courier IS the capture path) ────────

    @Override
    @Transactional
    public CodCollection collectCod(AssignmentId id, AgentId agent, long amountMinor) {
        DeliveryAssignmentEntity assignment = owned(id, agent);
        ShipmentEntity shipment = shipments.findById(assignment.getShipmentId()).orElseThrow();
        OrderId order = new OrderId(shipment.getOrderId());

        TenderView cod = tenders.tendersOf(order).stream()
                .filter(t -> t.kind() == TenderKind.COD)
                .findFirst()
                .orElseThrow(() -> new ApiErrors.UnprocessableException(
                        "this order has no cash-on-delivery tender"));
        if (cod.status() == TenderView.Status.SETTLED) {
            // §14 COD single-settle: a retried collect is a no-op.
            return new CodCollection(id, order.value(), cod.amount().amountMinor(),
                    cod.amount().currency(), true);
        }
        // The courier collects exactly what is owed — never more, never less.
        if (amountMinor != cod.amount().amountMinor()) {
            throw new ApiErrors.UnprocessableException("COD amount is "
                    + cod.amount().amountMinor() + " " + cod.amount().currency()
                    + " — collected " + amountMinor);
        }

        tenders.settle(cod.id(), "courier:" + agent.value(), assignment.getId());
        outbox.append(new StoreId(shipment.getStoreId()), "order", order.value(),
                "cod.collected", "{\"orderId\":\"" + order.value()
                        + "\",\"assignmentId\":\"" + assignment.getId()
                        + "\",\"agentId\":\"" + agent.value()
                        + "\",\"amountMinor\":" + amountMinor + "}");
        return new CodCollection(id, order.value(), amountMinor, cod.amount().currency(), false);
    }

    // ── internals ────────────────────────────────────────────────────────────

    /** Ownership check: another courier's (or store's) assignment is a 404 — never a hint. */
    private DeliveryAssignmentEntity owned(AssignmentId id, AgentId agent) {
        return assignments.findById(id.value())
                .filter(a -> a.getAgentId().equals(agent.value()))
                .orElseThrow(() -> new ApiErrors.NotFoundException("assignment", id.value()));
    }

    private boolean requiredDocsPresent(String agentId) {
        Set<DocKind> kinds = documents.findByAgentIdOrderByUploadedAtAsc(agentId).stream()
                .map(d -> DocKind.valueOf(d.getKind()))
                .collect(Collectors.toSet());
        return kinds.stream().anyMatch(PHOTO_ID::contains)
                && kinds.stream().anyMatch(VEHICLE_DOC::contains);
    }

    private DeliveryAgentEntity locked(AgentId id) {
        return agents.lockById(id.value())
                .orElseThrow(() -> new ApiErrors.NotFoundException("delivery agent", id.value()));
    }

    private AgentView toView(DeliveryAgentEntity agent) {
        List<DocumentView> docs = documents.findByAgentIdOrderByUploadedAtAsc(agent.getId()).stream()
                .map(d -> new DocumentView(d.getId(), DocKind.valueOf(d.getKind()),
                        d.getStatus(), d.getUploadedAt()))
                .toList();
        return new AgentView(new AgentId(agent.getId()), new StoreId(agent.getStoreId()),
                agent.getLegalName(), read(agent.getLegalAddressJson(), Address.class),
                agent.getPhone(), agent.isPhoneVerified(),
                read(agent.getVehicleJson(), Vehicle.class),
                agent.getIdDocJson() == null ? null : read(agent.getIdDocJson(), IdDocument.class),
                agent.status(), docs, agent.getRegisteredAt(), agent.getApprovedAt(),
                agent.getReviewedBy(), agent.getRejectReason());
    }

    private AssignmentView toView(DeliveryAssignmentEntity assignment) {
        return new AssignmentView(new AssignmentId(assignment.getId()),
                new ShipmentId(assignment.getShipmentId()), new AgentId(assignment.getAgentId()),
                new StoreId(assignment.getStoreId()), assignment.status(),
                assignment.getAssignedAt());
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiErrors.UnprocessableException(field + " is required");
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("unserializable agent field", e);
        }
    }

    private <T> T read(String raw, Class<T> type) {
        try {
            return json.readValue(raw, type);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt agent json", e);
        }
    }

    private Map<String, Object> readMap(String raw) {
        try {
            return json.readValue(raw,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("corrupt document blob_ref", e);
        }
    }

    private String quote(String raw) {
        try {
            return json.writeValueAsString(raw == null ? "" : raw);
        } catch (Exception e) {
            return "\"\"";
        }
    }
}
