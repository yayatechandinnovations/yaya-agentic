package com.yayatechandinnovations.yayasales.fulfillment.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** KYC scan: PII, {@code blobRef} is an AES-256-GCM envelope, never plaintext (§15). */
@Entity
@Table(name = "delivery_agent_documents")
public class DeliveryAgentDocumentEntity {

    @Id
    private String id;

    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Column(nullable = false)
    private String kind;

    @Column(name = "blob_ref", nullable = false)
    private String blobRef;

    @Column(nullable = false)
    private String status;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected DeliveryAgentDocumentEntity() {}

    public DeliveryAgentDocumentEntity(String id, String agentId, String kind, String blobRef) {
        this.id = id;
        this.agentId = agentId;
        this.kind = kind;
        this.blobRef = blobRef;
        this.status = "SUBMITTED";
        this.uploadedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getAgentId() { return agentId; }
    public String getKind() { return kind; }
    public String getBlobRef() { return blobRef; }
    public String getStatus() { return status; }
    public Instant getUploadedAt() { return uploadedAt; }
}
