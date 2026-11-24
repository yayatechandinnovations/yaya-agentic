package com.yayatechandinnovations.yayaagentic.persistence;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "channel_opt_outs")
@IdClass(ChannelOptOutEntity.PK.class)
public class ChannelOptOutEntity {

    @Id @Column(name = "sender_id", length = 64)
    private String senderId;

    @Id @Column(name = "end_user_phone", length = 20)
    private String endUserPhone;

    @Column(name = "opted_out_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime optedOutAt;

    @Column(name = "reason", length = 64)
    private String reason;

    /** On a platform sender, the workspace that wrote to the phone last before the STOP (§9.4). */
    @Column(name = "attributed_tenant_id", length = 64)
    private String attributedTenantId;

    protected ChannelOptOutEntity() {}

    public ChannelOptOutEntity(String senderId, String endUserPhone, String reason) {
        this.senderId = senderId;
        this.endUserPhone = endUserPhone;
        this.reason = reason;
    }

    public String getSenderId() { return senderId; }
    public String getEndUserPhone() { return endUserPhone; }
    public OffsetDateTime getOptedOutAt() { return optedOutAt; }
    public String getReason() { return reason; }
    public String getAttributedTenantId() { return attributedTenantId; }
    public void setAttributedTenantId(String v) { this.attributedTenantId = v; }

    public static class PK implements Serializable {
        private String senderId; private String endUserPhone;
        public PK() {}
        public PK(String senderId, String endUserPhone) {
            this.senderId = senderId; this.endUserPhone = endUserPhone;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(senderId, pk.senderId)
                    && Objects.equals(endUserPhone, pk.endUserPhone);
        }
        @Override public int hashCode() { return Objects.hash(senderId, endUserPhone); }
    }
}
